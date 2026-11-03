package com.aia.voiceinsights.api.service;

import com.aia.voiceinsights.api.model.CustomerProfile;
import com.aia.voiceinsights.api.service.ReadAloudService.Reading;
import com.aia.voiceinsights.api.service.ReadAloudService.Section;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.TextStyle;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * What Spark remembers about the advisor's customers, so "Hey Spark, brief me on Nick" works from anywhere, on any device.
 *
 * <ul>
 *   <li><b>People, not rows.</b> Every debrief is already saved. This groups an advisor's debriefs into people by name, so a customer debriefed
 *       three times is one person with a history, newest first.</li>
 *   <li><b>Names as spoken.</b> A name heard by a microphone is matched loosely (nicknames, small misspellings, similar sounds). If it fits more
 *       than one person, nothing is guessed: the caller is told to ask which one.</li>
 *   <li><b>The brief</b> is about the latest debrief, plus what changed since the one before. The "what changed" part is worked out from the
 *       stored facts, never by the language model, so it cannot invent a change.</li>
 * </ul>
 */
@Service
public class CustomerMemoryService {

    private static final Logger log = LoggerFactory.getLogger(CustomerMemoryService.class);

    /** A person the advisor has debriefed. {@code debriefs} is newest first and never empty. */
    public record Person(String key, String name, List<CustomerProfile> debriefs) {
        public CustomerProfile latest() { return debriefs.get(0); }
    }

    /** What the screen and the voice need to know about a person, without the whole debrief. */
    public record Candidate(String id, String name, Integer age, String occupation, String lastDebriefAt, String lastDebriefSpoken,
                            int debriefs, boolean hasSuggestions) {}

    /** {@code status}: found (reading is set), choose (candidates is set: ask which), none (say is set). */
    public record Brief(String status, String say, Candidate customer, Reading reading, List<Candidate> candidates) {}

    private record Script(String script) {}

    private static ZoneId zone() { return BusinessUnits.zone(); }
    private static final int MAX_CANDIDATES = 4;
    /** A name must fit this well to count at all, and anything within this of the best fit is "also a match". */
    static final double MIN_SCORE = 0.7;
    static final double TIE_BAND = 0.05;

    private static final Set<String> HONORIFICS = Set.of("mr", "mrs", "ms", "miss", "dr", "mdm", "madam", "sir", "mister", "uncle", "auntie", "aunty", "ah");
    private static final Set<String> FILLER = Set.of("on", "about", "the", "customer", "client", "for", "me", "brief", "debrief", "of", "my", "case", "file", "with");

    /** Common short and long forms of the same first name, so "Nick" finds "Nicholas". Each row is one name. */
    private static final List<Set<String>> NICKNAMES = List.of(
            Set.of("nick", "nic", "nik", "nicholas", "nicolas", "nico"), Set.of("mike", "mick", "michael", "mikey"), Set.of("bob", "bobby", "rob", "robert", "robbie"),
            Set.of("will", "bill", "billy", "william", "willy"), Set.of("jim", "jimmy", "james", "jamie"), Set.of("tom", "tommy", "thomas"),
            Set.of("dave", "david", "davy"), Set.of("steve", "steven", "stephen"), Set.of("chris", "christopher"), Set.of("alex", "alexander", "alexandra", "alexis"),
            Set.of("sam", "samuel", "samantha"), Set.of("ben", "benny", "benjamin"), Set.of("dan", "danny", "daniel"), Set.of("joe", "joey", "joseph"),
            Set.of("tony", "anthony", "antony"), Set.of("ed", "eddie", "edward", "edwin"), Set.of("matt", "matthew"), Set.of("andy", "drew", "andrew"),
            Set.of("pat", "patrick", "paddy"), Set.of("rick", "rich", "richard", "dick"), Set.of("sue", "susan", "suzanne"), Set.of("kate", "katie", "kathy", "katherine", "catherine"),
            Set.of("liz", "beth", "elizabeth", "lizzie"), Set.of("jen", "jenny", "jennifer"), Set.of("maggie", "meg", "margaret"), Set.of("vicky", "vicki", "victoria"),
            Set.of("jon", "john", "johnny", "jonathan"), Set.of("pete", "peter"), Set.of("ken", "kenneth"), Set.of("ray", "raymond"), Set.of("greg", "gregory"));

    private static final String FACTS_SYSTEM = """
            You brief a financial advisor who is driving and can only listen. Using ONLY the debrief notes given, write a spoken briefing of
            90 to 140 words in plain English.

            Respond with ONLY a JSON object, no markdown fences: {"script": "..."}

            Order: who the customer is (name, age, job, family) in one sentence; what matters most to them; what they already have; anything
            worth clarifying; what is still missing; the first two questions to ask next time. Say plainly that suggestions have not been
            prepared yet, and the advisor can approve the debrief in the app to start them.
            The CUSTOMER, DEPENDANTS, INCOME, BUDGET, EXISTING COVER and GOALS lines are the confirmed facts. The transcript is only background:
            if it disagrees with those lines (an age, a number of children, an amount), say what the confirmed lines say and never the transcript.
            Rules: no lists, headings, markdown, emojis or symbols; say numbers and money as you would speak them. Never invent a fact, product,
            name or figure that is not in the notes. If a detail is not in the notes, leave it out. Start with "Here's your briefing on
            <first name>." and end with "That's everything from the debrief."
            """;

    private final CustomerProfileStore profiles;
    private final RecommendationStore recommendations;
    private final ReadAloudService reading;
    private final HedgedChat chat;
    private final boolean includeUnowned;
    private final ObjectMapper mapper = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public CustomerMemoryService(CustomerProfileStore profiles, RecommendationStore recommendations, ReadAloudService reading, HedgedChat chat,
                                 @Value("${voice.memory.include-unowned:false}") boolean includeUnowned) {
        this.profiles = profiles;
        this.recommendations = recommendations;
        this.reading = reading;
        this.chat = chat;
        this.includeUnowned = includeUnowned;
    }

    // ── who the advisor has debriefed ───────────────────────────────────────────────────────────────────────────

    public List<Person> people(String advisorId) {
        return group(profiles.findForAdvisor(advisorId, includeUnowned, 800));
    }

    /** The advisor's people, most recently debriefed first, for the home page. */
    public List<Candidate> recent(String advisorId, int limit) {
        Map<String, String> runs = recommendations.latestRunIdByProfile();
        return people(advisorId).stream().limit(Math.max(1, Math.min(limit, 20))).map(p -> candidate(p, runs, Instant.now())).toList();
    }

    public Optional<Person> personFor(String advisorId, String profileId) {
        return people(advisorId).stream().filter(p -> p.debriefs().stream().anyMatch(d -> profileId.equals(d.getId()))).findFirst();
    }

    // ── "brief me on <name>" ────────────────────────────────────────────────────────────────────────────────────

    public Brief briefByName(String advisorId, String spoken) {
        List<Person> matches = find(people(advisorId), spoken);
        String heard = spoken == null ? "" : spoken.strip();
        if (matches.isEmpty()) {
            return new Brief("none", heard.isEmpty() ? "Whose debrief would you like?" : "I don't have a debrief for " + heard + ".", null, null, List.of());
        }
        if (matches.size() > 1) {
            Map<String, String> runs = recommendations.latestRunIdByProfile();
            Instant now = Instant.now();
            return new Brief("choose", null, null, null, matches.stream().map(p -> candidate(p, runs, now)).toList());
        }
        return briefOf(matches.get(0));
    }

    public Optional<Brief> briefByProfile(String advisorId, String profileId) {
        return personFor(advisorId, profileId).map(this::briefOf);
    }

    private Brief briefOf(Person p) {
        Map<String, String> runs = recommendations.latestRunIdByProfile();
        Instant now = Instant.now();
        Reading r = compose(p, runs, now);
        return new Brief("found", null, candidate(p, runs, now), r, List.of());
    }

    /** The reading: when you last debriefed them and what changed since the one before, then the briefing itself. */
    private Reading compose(Person p, Map<String, String> runs, Instant now) {
        CustomerProfile latest = p.latest();
        String first = firstName(p.name());
        String lead = lead(p, now);
        List<Section> sections = new ArrayList<>();
        sections.add(new Section("Last debrief", lead));

        List<Section> main = List.of();
        String runId = runs.get(latest.getId());
        if (runId != null) main = reading.read(runId, "brief").map(Reading::sections).orElse(List.of());
        if (main.isEmpty()) main = List.of(new Section("Briefing", factsBrief(latest, first)));
        sections.addAll(main);
        return new Reading("Briefing on " + first, sections);
    }

    /** "You last debriefed X yesterday. Compared with the debrief before that, ..." The earlier one is only dated when it was on another day. */
    static String lead(Person p, Instant now) {
        CustomerProfile latest = p.latest();
        String when = spokenWhen(latest.getCreatedAt(), now);
        StringBuilder lead = new StringBuilder("You last debriefed ").append(p.name()).append(' ').append(when).append('.');
        if (p.debriefs().size() > 1) {
            CustomerProfile prev = p.debriefs().get(1);
            String prevWhen = spokenWhen(prev.getCreatedAt(), now);
            List<String> changes = changes(prev, latest);
            lead.append(" Compared with ").append(prevWhen.equals(when) ? "the debrief before that" : "your debrief " + prevWhen).append(", ");
            lead.append(changes.isEmpty() ? "nothing in the facts has changed." : String.join(" ", changes));
        }
        return lead.toString();
    }

    /** A briefing from the debrief alone (no suggestions yet): the model writes it from the notes; if it cannot, a plain summary is read instead. */
    private String factsBrief(CustomerProfile p, String first) {
        try {
            StringBuilder in = new StringBuilder(notes(p));
            String t = p.getRawTranscript() == null ? "" : p.getRawTranscript().strip();
            if (!t.isEmpty()) in.append("\nDEBRIEF TRANSCRIPT (end of it):\n").append(t.length() > 2500 ? t.substring(t.length() - 2500) : t).append('\n');
            in.append("\nFIRST NAME: ").append(first);
            Script s = mapper.readValue(chat.json(FACTS_SYSTEM, in.toString(), 0.3, 420), Script.class);
            if (s.script() != null && !s.script().isBlank()) return ReadAloudService.spoken(s.script());
        } catch (Exception e) {
            log.warn("CustomerMemoryService: briefing from notes failed, reading a plain summary: {}", e.toString());
        }
        return plainSummary(p, first);
    }

    private static String notes(CustomerProfile p) {
        StringBuilder sb = new StringBuilder();
        sb.append("CUSTOMER: ").append(nz(p.getCustomerName())).append(", age ").append(p.getAge() == null ? "not given" : p.getAge()).append(", ").append(nz(p.getOccupation())).append('\n');
        if (!p.getDependantsList().isEmpty()) sb.append("DEPENDANTS: ").append(String.join("; ", p.getDependantsList())).append('\n');
        else if (p.getDependents() != null) sb.append("DEPENDANTS: ").append(p.getDependents()).append('\n');
        if (p.getIncomeBand() != null) sb.append("INCOME: ").append(p.getIncomeBand()).append('\n');
        if (p.getBudgetNotes() != null) sb.append("BUDGET: ").append(p.getBudgetNotes()).append('\n');
        if (p.getExistingPolicies() != null && !p.getExistingPolicies().isEmpty()) sb.append("EXISTING COVER: ").append(String.join("; ", p.getExistingPolicies())).append('\n');
        if (p.getGoalsAndConcerns() != null && !p.getGoalsAndConcerns().isEmpty()) sb.append("GOALS AND CONCERNS: ").append(String.join("; ", p.getGoalsAndConcerns())).append('\n');
        if (p.getNotes() != null) sb.append("NOTES: ").append(p.getNotes()).append('\n');
        return sb.toString();
    }

    static String plainSummary(CustomerProfile p, String first) {
        StringBuilder sb = new StringBuilder("Here's your briefing on ").append(first).append(". ");
        sb.append(nz(p.getCustomerName(), first));
        if (p.getAge() != null) sb.append(", ").append(p.getAge());
        if (p.getOccupation() != null && !p.getOccupation().isBlank()) sb.append(", ").append(p.getOccupation());
        sb.append(". ");
        if (!p.getDependantsList().isEmpty()) sb.append("Dependants: ").append(String.join(", ", p.getDependantsList())).append(". ");
        if (p.getGoalsAndConcerns() != null && !p.getGoalsAndConcerns().isEmpty()) sb.append("What matters to them: ").append(String.join(", ", p.getGoalsAndConcerns())).append(". ");
        if (p.getExistingPolicies() != null && !p.getExistingPolicies().isEmpty()) sb.append("They already have ").append(String.join(", ", p.getExistingPolicies())).append(". ");
        if (p.getBudgetNotes() != null && !p.getBudgetNotes().isBlank()) sb.append("Budget: ").append(p.getBudgetNotes()).append(". ");
        sb.append("Suggestions have not been prepared yet.");
        return ReadAloudService.spoken(sb.toString());
    }

    // ── grouping debriefs into people ───────────────────────────────────────────────────────────────────────────

    /** Debriefs that have a customer name and something said, grouped into people (newest debrief first within each, most recently debriefed person first). */
    static List<Person> group(List<CustomerProfile> all) {
        Map<String, List<CustomerProfile>> byKey = new LinkedHashMap<>();
        all.stream()
                .filter(p -> p.getCustomerName() != null && !p.getCustomerName().isBlank() && p.getCreatedAt() != null
                        && p.getRawTranscript() != null && !p.getRawTranscript().isBlank())
                .sorted(Comparator.comparing(CustomerProfile::getCreatedAt).reversed())
                .forEach(p -> byKey.computeIfAbsent(String.join(" ", tokens(p.getCustomerName())), k -> new ArrayList<>()).add(p));

        // A first name on its own ("Nick") belongs to the one person with that first name, when there is exactly one.
        for (String single : List.copyOf(byKey.keySet())) {
            if (single.isBlank() || single.contains(" ")) continue;
            List<String> fulls = byKey.keySet().stream().filter(k -> k.contains(" ") && sameName(k.split(" ")[0], single)).toList();
            if (fulls.size() == 1) {
                List<CustomerProfile> merged = new ArrayList<>(byKey.get(fulls.get(0)));
                merged.addAll(byKey.remove(single));
                merged.sort(Comparator.comparing(CustomerProfile::getCreatedAt).reversed());
                byKey.put(fulls.get(0), merged);
            }
        }
        return byKey.entrySet().stream()
                .filter(e -> !e.getKey().isBlank())
                .map(e -> new Person(e.getKey(), displayName(e.getValue()), List.copyOf(e.getValue())))
                .sorted(Comparator.comparing((Person p) -> p.latest().getCreatedAt()).reversed())
                .toList();
    }

    /** The fullest way the name was written, preferring the most recent. */
    private static String displayName(List<CustomerProfile> debriefs) {
        return debriefs.stream().map(d -> d.getCustomerName().strip())
                .max(Comparator.comparingInt((String n) -> n.split("\\s+").length)).orElse(debriefs.get(0).getCustomerName().strip());
    }

    // ── matching a spoken name ──────────────────────────────────────────────────────────────────────────────────

    /** The people a spoken name fits, best first; several only when they fit equally well (the caller must then ask which). */
    static List<Person> find(List<Person> people, String spoken) {
        List<String> want = tokens(spoken).stream().filter(t -> !HONORIFICS.contains(t) && !FILLER.contains(t)).toList();
        if (want.isEmpty()) return List.of();
        record Scored(Person person, double score) {}
        List<Scored> scored = people.stream().map(p -> new Scored(p, score(want, List.of(p.key().split(" ")))))
                .filter(s -> s.score() >= MIN_SCORE).sorted(Comparator.comparingDouble(Scored::score).reversed()).toList();
        if (scored.isEmpty()) return List.of();
        double best = scored.get(0).score();
        return scored.stream().filter(s -> s.score() >= best - TIE_BAND).limit(MAX_CANDIDATES).map(Scored::person).toList();
    }

    /** How well every spoken word fits some word of the name, 0 to 1. One word that fits nothing makes it 0. */
    static double score(List<String> want, List<String> name) {
        double sum = 0;
        for (String w : want) {
            double best = 0;
            for (String n : name) best = Math.max(best, tokenScore(w, n));
            if (best < MIN_SCORE) return 0;
            sum += best;
        }
        return sum / want.size();
    }

    static double tokenScore(String heard, String name) {
        if (heard.equals(name)) return 1.0;
        if (sameName(heard, name)) return 0.95;
        if (heard.length() >= 3 && name.startsWith(heard)) return 0.85;
        int d = distance(heard, name);
        int len = Math.min(heard.length(), name.length());
        if (len >= 4 && d <= 1) return 0.8;
        if (len >= 6 && d <= 2) return 0.75;
        if (len >= 3 && soundex(heard).equals(soundex(name))) return 0.7;
        return 0;
    }

    static boolean sameName(String a, String b) {
        if (a.equals(b)) return true;
        return NICKNAMES.stream().anyMatch(g -> g.contains(a) && g.contains(b));
    }

    static List<String> tokens(String s) {
        if (s == null) return List.of();
        // Accents are dropped from Latin names only (a spoken "Nguyen" matches "Nguyễn"); in Thai and other scripts the marks are part of the letters.
        boolean latinOnly = s.codePoints().noneMatch(c -> Character.isLetter(c) && Character.UnicodeScript.of(c) != Character.UnicodeScript.LATIN);
        String plain = (latinOnly ? Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}+", "") : s).toLowerCase(Locale.ROOT);
        return java.util.Arrays.stream(plain.split("[^\\p{L}]+")).filter(t -> !t.isBlank()).collect(Collectors.toList());
    }

    static int distance(String a, String b) {
        int[] prev = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            int[] cur = new int[b.length() + 1];
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1));
            }
            prev = cur;
        }
        return prev[b.length()];
    }

    /** American Soundex: words that sound alike share a code ("Smith" and "Smyth"). Non-Latin names give "" and so never match by sound. */
    static String soundex(String s) {
        String codes = "01230120022455012623010202"; // a..z
        StringBuilder out = new StringBuilder();
        char last = 0;
        for (char c : s.toLowerCase(Locale.ROOT).toCharArray()) {
            if (c < 'a' || c > 'z') return "";
            char code = codes.charAt(c - 'a');
            if (out.length() == 0) { out.append(Character.toUpperCase(c)); last = code; continue; }
            if (code != '0' && code != last) out.append(code);
            if (c != 'h' && c != 'w') last = code;
            if (out.length() == 4) break;
        }
        while (out.length() < 4 && out.length() > 0) out.append('0');
        return out.toString();
    }

    // ── what changed ────────────────────────────────────────────────────────────────────────────────────────────

    /** Plain sentences about what is different in {@code cur} compared with {@code prev}. Worked out from the stored facts only. */
    static List<String> changes(CustomerProfile prev, CustomerProfile cur) {
        List<String> out = new ArrayList<>();
        if (cur.getAge() != null && prev.getAge() != null && !cur.getAge().equals(prev.getAge())) out.add("their age is now " + cur.getAge() + ", it was " + prev.getAge() + ".");
        if (differs(cur.getOccupation(), prev.getOccupation()) && notBlank(cur.getOccupation())) {
            out.add("their work is now given as " + cur.getOccupation().strip() + (notBlank(prev.getOccupation()) ? ", it was " + prev.getOccupation().strip() : "") + ".");
        }
        List<String> newDeps = added(prev.getDependantsList(), cur.getDependantsList());
        if (!newDeps.isEmpty()) out.add("new dependants: " + String.join(", ", newDeps) + ".");
        else if (cur.getDependents() != null && prev.getDependents() != null && !cur.getDependents().equals(prev.getDependents())) {
            out.add("they now have " + cur.getDependents() + " dependants, it was " + prev.getDependents() + ".");
        }
        List<String> newCover = added(prev.getExistingPolicies(), cur.getExistingPolicies());
        if (!newCover.isEmpty()) out.add("new existing cover: " + String.join(", ", newCover) + ".");
        List<String> newGoals = added(prev.getGoalsAndConcerns(), cur.getGoalsAndConcerns());
        if (!newGoals.isEmpty()) out.add("new goals or concerns: " + String.join(", ", newGoals.stream().limit(3).toList()) + ".");
        if (differs(cur.getBudgetNotes(), prev.getBudgetNotes()) && notBlank(cur.getBudgetNotes())) out.add("the budget now reads: " + cur.getBudgetNotes().strip() + ".");
        if (out.isEmpty()) return out;
        // "Compared with X, " is already said: the first change starts lower case, the rest are sentences of their own.
        List<String> sentences = new ArrayList<>();
        for (int i = 0; i < out.size(); i++) sentences.add(i == 0 ? out.get(i) : Character.toUpperCase(out.get(i).charAt(0)) + out.get(i).substring(1));
        return sentences;
    }

    private static List<String> added(List<String> before, List<String> after) {
        if (after == null) return List.of();
        Set<String> had = before == null ? Set.of() : before.stream().map(CustomerMemoryService::norm).collect(Collectors.toSet());
        return after.stream().filter(x -> notBlank(x) && !had.contains(norm(x))).map(String::strip).toList();
    }

    private static String norm(String s) { return String.join(" ", tokens(s)); }
    private static boolean notBlank(String s) { return s != null && !s.isBlank(); }
    private static boolean differs(String a, String b) { return !norm(a == null ? "" : a).equals(norm(b == null ? "" : b)); }
    private static String nz(String s) { return s == null ? "" : s; }
    private static String nz(String s, String fallback) { return notBlank(s) ? s : fallback; }

    // ── wording ─────────────────────────────────────────────────────────────────────────────────────────────────

    static String firstName(String name) {
        String[] parts = name == null ? new String[0] : name.strip().split("\\s+");
        return parts.length == 0 || parts[0].isBlank() ? "the customer" : parts[0];
    }

    private Candidate candidate(Person p, Map<String, String> runs, Instant now) {
        CustomerProfile l = p.latest();
        return new Candidate(l.getId(), p.name(), l.getAge(), l.getOccupation(), l.getCreatedAt().toString(), spokenWhen(l.getCreatedAt(), now),
                p.debriefs().size(), runs.containsKey(l.getId()));
    }

    /** "today", "yesterday", "on Tuesday" (within the last week), else "on 28 October" (with the year if it is not this one). */
    static String spokenWhen(Instant at, Instant now) {
        LocalDate d = at.atZone(zone()).toLocalDate();
        LocalDate today = now.atZone(zone()).toLocalDate();
        long days = ChronoUnit.DAYS.between(d, today);
        if (days <= 0) return "today";
        if (days == 1) return "yesterday";
        if (days < 7) return "on " + d.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.ENGLISH);
        String md = d.getDayOfMonth() + " " + d.getMonth().getDisplayName(TextStyle.FULL, Locale.ENGLISH);
        return "on " + md + (d.getYear() == today.getYear() ? "" : " " + d.getYear());
    }
}
