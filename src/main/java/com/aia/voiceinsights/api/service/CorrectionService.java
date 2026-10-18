package com.aia.voiceinsights.api.service;

import com.aia.voiceinsights.api.model.CustomerProfile;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.Set;

/**
 * Corrections the advisor speaks to Juno on the review page: "make that a monthly budget", "her age is 39", "the name is Priya,
 * P R I Y A". It only turns the words into one proposed change to one field of the record. It never saves anything: the page applies
 * the change, shows what changed, and lets the advisor undo it.
 */
@Service
public class CorrectionService {

    private static final Logger log = LoggerFactory.getLogger(CorrectionService.class);

    /** {@code field} is one of the record fields, or "none" when the words were not a correction. {@code say} is Juno's one-line reply. */
    public record Result(String field, String value, String say) {}

    private record Llm(String field, String value, String say) {}

    private static final Set<String> FIELDS = Set.of("customerName", "age", "occupation", "dependents", "budgetNotes");
    private static final String NONE_SAY = "I didn't catch a correction. Try: \"the name is Priya Nair\" or \"make that a monthly budget\".";

    private static final String SYSTEM = """
            You turn ONE spoken correction by a financial advisor into a single change to a customer record. The advisor is reviewing what
            a speech recogniser heard, so names and numbers are often what they are fixing.

            Respond with ONLY a JSON object, no markdown fences:
            {"field": "customerName" | "age" | "occupation" | "dependents" | "budgetNotes" | "none", "value": "the new value", "say": "one short sentence"}

            Rules:
            - Change exactly one field. If the words are not a correction of one of these fields, use "none".
            - customerName: use the spelling the advisor gives. If they spell it out ("P R I Y A, N A I R", "P-R-I-Y-A Nair"), join the letters
              into words and capitalise each word. Keep the number of words close to the current name unless they clearly give more or fewer.
            - age and dependents: "value" is digits only ("39", "3").
            - budgetNotes: keep the amount the record already has. A unit ("monthly", "a year", "per month") is added or replaced, e.g. current
              "Can put aside about $400" + "make that monthly" becomes "Can put aside about $400 per month". A new amount replaces the old one.
            - occupation: the job in plain words, lower case unless a proper noun.
            - Never invent a value the advisor did not say. If unsure what they meant, use "none" and ask them to say it again in "say".
            - "say": one short sentence in plain English confirming exactly what changes ("Name is now Priya Nair.", "Budget is now $400 per month.",
              "Age is now 39.") or asking them to repeat. This is a data correction, not a product suggestion: never use the words "suggest",
              "suggestion" or "recommend" in "say", whatever other wording rules you were given.
            """;

    private final HedgedChat chat;
    private final CustomerProfileStore profileStore;
    private final ObjectMapper mapper = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public CorrectionService(HedgedChat chat, CustomerProfileStore profileStore) {
        this.chat = chat;
        this.profileStore = profileStore;
    }

    /** @return empty when the profile does not exist or the model gave nothing usable. */
    public Optional<Result> interpret(String profileId, String utterance) {
        if (profileId == null || profileId.isBlank() || utterance == null || utterance.isBlank()) return Optional.empty();
        CustomerProfile p = profileStore.findById(profileId).orElse(null);
        if (p == null) return Optional.empty();
        String heard = utterance.strip();
        if (heard.length() > 400) heard = heard.substring(0, 400);
        try {
            String user = "CURRENT RECORD:\n"
                    + "customerName: " + nz(p.getCustomerName()) + "\n"
                    + "age: " + (p.getAge() == null ? "not set" : p.getAge()) + "\n"
                    + "occupation: " + nz(p.getOccupation()) + "\n"
                    + "dependents: " + (p.getDependents() == null ? "not set" : p.getDependents()) + "\n"
                    + "budgetNotes: " + nz(p.getBudgetNotes()) + "\n\n"
                    + "THE ADVISOR SAID: \"" + heard.replace("\"", "'") + "\"";
            Llm l = mapper.readValue(chat.json(SYSTEM, user, 0.1, 160), Llm.class);
            return Optional.of(checked(l));
        } catch (Exception e) {
            log.warn("CorrectionService: interpret failed: {}", e.toString());
            return Optional.empty();
        }
    }

    /** A proposed change stands only if its field is real and its value is sane; anything else is "not a correction". */
    private static Result checked(Llm l) {
        String field = l.field() == null ? "none" : l.field().strip();
        String say = l.say() == null || l.say().isBlank() ? NONE_SAY : l.say().strip();
        String value = l.value() == null ? "" : l.value().strip();
        if (!FIELDS.contains(field) || value.isEmpty() || value.length() > 120) return new Result("none", "", "none".equals(field) ? say : NONE_SAY);
        if ("age".equals(field) || "dependents".equals(field)) {
            String digits = value.replaceAll("[^0-9]", "");
            if (digits.isEmpty() || digits.length() > 3) return new Result("none", "", NONE_SAY);
            int n = Integer.parseInt(digits);
            if (("age".equals(field) && n > 120) || ("dependents".equals(field) && n > 20)) return new Result("none", "", NONE_SAY);
            value = String.valueOf(n);
        }
        return new Result(field, value, say);
    }

    private static String nz(String s) {
        return s == null || s.isBlank() ? "not set" : s;
    }
}
