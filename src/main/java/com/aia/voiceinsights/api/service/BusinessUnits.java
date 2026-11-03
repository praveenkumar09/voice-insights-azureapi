package com.aia.voiceinsights.api.service;

import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The AIA business units Spark can be set up for, and the one that is selected now (chosen by an admin, kept in {@link AppSettingsService}).
 * Everything that used to say "AIA Singapore" or "SGD" reads from here: the identity in every prompt, the currency, the clock, what the
 * speech model is told about the country and the languages Spark offers.
 *
 * <p>Money: the product documents in the knowledge base state their amounts in SGD, because they are Singapore's. For any other unit
 * {@link #convertAmounts} turns every "SGD 100,000" / "S$100,000" the model is shown (and anything it still writes) into the unit's
 * currency at the unit's rate, so the model reasons and writes in one currency and the customer only ever sees that one.
 *
 * <p>The rates are INDICATIVE and fixed, for the proof of concept. A real deployment must replace them with the unit's own product
 * documents (priced in its own currency) or with treasury rates. They are shown to the admin so nobody mistakes them for live rates.
 */
public final class BusinessUnits {

    private BusinessUnits() {}

    /**
     * @param id               what the settings and the browser use
     * @param name             the unit's name as customers know it, e.g. "AIA Thailand"
     * @param country          the place, as prompts name it
     * @param currency         ISO code: THB
     * @param symbol           shown before an amount in the UI when the code is not wanted ("S$"); the code is used when this is null
     * @param perSgd           how many units of {@code currency} one SGD is worth (indicative)
     * @param zone             time zone of the unit
     * @param defaultLanguages the languages offered until an admin chooses others (codes from {@link Languages})
     * @param incomeExample    a believable monthly income in the unit's currency, for form hints
     * @param budgetExample    a believable monthly premium budget in the unit's currency
     * @param sttContext       what the speech model is told about the setting of the conversation
     */
    public record Bu(String id, String name, String country, String currency, String symbol, double perSgd, String zone,
                     List<String> defaultLanguages, String incomeExample, String budgetExample, String sttContext) {}

    public static final String DEFAULT = "sg";

    private static final Map<String, Bu> ALL = new LinkedHashMap<>();

    static {
        add(new Bu("sg", "AIA Singapore", "Singapore", "SGD", "S$", 1.0, "Asia/Singapore", List.of("en", "zh", "ms", "ta", "th", "fil", "vi"),
                "S$6,000–8,000 / month", "S$400 a month",
                "Insurance conversation in Singapore. Expect Singlish, code-switching, Indian, Malay and Chinese names, and terms like "
                        + "CPF, MediSave, critical illness, term life, premium, dependents."));
        add(new Bu("th", "AIA Thailand", "Thailand", "THB", "฿", 26.0, "Asia/Bangkok", List.of("en", "th"),
                "฿50,000–80,000 / month", "฿5,000 a month",
                "Insurance conversation in Thailand. Expect Thai names and Thai speech with English insurance words mixed in: "
                        + "critical illness, term life, premium, dependents."));
        add(new Bu("hk", "AIA Group (Hong Kong)", "Hong Kong", "HKD", "HK$", 5.9, "Asia/Hong_Kong", List.of("en", "zh"),
                "HK$40,000–60,000 / month", "HK$3,000 a month",
                "Insurance conversation in Hong Kong. Expect English mixed with Mandarin or Cantonese terms, Chinese names, and terms like "
                        + "critical illness, term life, premium, dependents."));
        add(new Bu("cn", "AIA China", "China", "CNY", "¥", 5.4, "Asia/Shanghai", List.of("en", "zh"),
                "¥20,000–30,000 / month", "¥2,000 a month",
                "Insurance conversation in China. Expect Mandarin with English insurance words mixed in, Chinese names, and terms like "
                        + "critical illness, term life, premium, dependents."));
        add(new Bu("my", "AIA Malaysia", "Malaysia", "MYR", "RM", 3.3, "Asia/Kuala_Lumpur", List.of("en", "ms", "zh", "ta"),
                "RM6,000–9,000 / month", "RM500 a month",
                "Insurance conversation in Malaysia. Expect English, Bahasa Melayu, Mandarin and Tamil mixed together, Malay, Chinese and Indian "
                        + "names, and terms like critical illness, term life, premium, dependents."));
        add(new Bu("id", "AIA Indonesia", "Indonesia", "IDR", "Rp", 12000.0, "Asia/Jakarta", List.of("en"),
                "Rp15,000,000–25,000,000 / month", "Rp1,500,000 a month",
                "Insurance conversation in Indonesia. Expect Indonesian names and Indonesian speech with English insurance words mixed in: "
                        + "critical illness, term life, premium, dependents."));
        add(new Bu("ph", "AIA Philippines", "the Philippines", "PHP", "₱", 43.0, "Asia/Manila", List.of("en", "fil"),
                "₱40,000–60,000 / month", "₱3,000 a month",
                "Insurance conversation in the Philippines. Expect Taglish (Tagalog with English mixed in), Filipino names, and terms like "
                        + "critical illness, term life, premium, dependents."));
        add(new Bu("vn", "AIA Vietnam", "Vietnam", "VND", "₫", 19500.0, "Asia/Ho_Chi_Minh", List.of("en", "vi"),
                "25,000,000–40,000,000 ₫ / month", "2,500,000 ₫ a month",
                "Insurance conversation in Vietnam. Expect Vietnamese names and Vietnamese speech with English insurance words mixed in: "
                        + "critical illness, term life, premium, dependents."));
    }

    private static void add(Bu b) { ALL.put(b.id(), b); }

    private static volatile Bu current = ALL.get(DEFAULT);

    public static List<Bu> all() { return List.copyOf(ALL.values()); }

    public static boolean isSupported(String id) { return id != null && ALL.containsKey(id.toLowerCase()); }

    public static Bu of(String id) { return id != null && ALL.containsKey(id.toLowerCase()) ? ALL.get(id.toLowerCase()) : ALL.get(DEFAULT); }

    public static Bu current() { return current; }

    static void setCurrent(String id) { current = of(id); }

    public static ZoneId zone() { return ZoneId.of(current.zone()); }

    private static boolean isSingapore() { return "sg".equals(current.id()); }

    /** The name of the selected unit, as it should be written wherever "AIA Singapore" was. */
    public static String name() { return current.name(); }

    // ── prompts ──────────────────────────────────────────────────────────────────────────────────────────────

    private static final Pattern SG_NAME = Pattern.compile("AIA (?:Singapore|Singapura|新加坡|สิงคโปร์)");
    private static final Pattern SG_WORD = Pattern.compile("\\bSingapore\\b");

    /**
     * Instruction text written for Singapore, made to be about the selected unit: "AIA Singapore" becomes its name, "Singapore" its country, and
     * every amount in SGD its currency. Nothing changes for Singapore.
     */
    public static String localize(String text) {
        if (text == null || isSingapore()) return text;
        String t = text.replace("ஏஐஏ சிங்கப்பூரின்", current.name() + "-இன்");
        t = SG_NAME.matcher(t).replaceAll(Matcher.quoteReplacement(current.name()));
        t = SG_WORD.matcher(t).replaceAll(Matcher.quoteReplacement(current.country()));
        return convertAmounts(t);
    }

    /** What a model is told about the money, added to a system prompt when the unit is not Singapore. */
    public static String currencyNote() {
        if (isSingapore()) return "";
        return "\n\nCURRENCY: this conversation is for " + current.name() + ". Every amount you are given or write is in " + current.currency()
                + " (" + current.country() + "). Write amounts as \"" + current.currency() + " 2,500,000\". Never write SGD, S$ or any other currency.";
    }

    /** How a currency is said aloud ("2,600,000 baht"). */
    public static String currencyWords() {
        return switch (current.currency()) {
            case "THB" -> "baht";
            case "HKD" -> "Hong Kong dollars";
            case "CNY" -> "yuan";
            case "MYR" -> "ringgit";
            case "IDR" -> "rupiah";
            case "PHP" -> "pesos";
            case "VND" -> "dong";
            default -> "Singapore dollars";
        };
    }

    // ── money ────────────────────────────────────────────────────────────────────────────────────────────────

    private static final String NUM = "\\d[\\d,]*(?:\\.\\d+)?";
    private static final String SUFFIX = "(?:\\s?(?:k|K|m|M|million)\\b)?";
    private static final Pattern SGD_AMOUNT = Pattern.compile(
            "(?:SGD|(?<![A-Za-z])S\\$|(?<![A-Za-z$])\\$)\\s?(" + NUM + ")(" + SUFFIX + ")(?:(\\s?(?:-|–|to)\\s?)(?:(?:SGD|(?<![A-Za-z])S\\$|(?<![A-Za-z$])\\$)\\s?)?(" + NUM + ")(" + SUFFIX + "))?");

    /** Every SGD amount in {@code text} written in the selected unit's currency; text for Singapore is returned as it is. */
    public static String convertAmounts(String text) {
        if (text == null || isSingapore()) return text;
        Bu bu = current;
        Matcher m = SGD_AMOUNT.matcher(text);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String first = money(m.group(1), m.group(2), bu);
            String s = bu.currency() + " " + first;
            if (m.group(4) != null) s += ("to".equals(m.group(3).strip()) ? " to " : "–") + money(m.group(4), m.group(5), bu);
            m.appendReplacement(out, Matcher.quoteReplacement(s));
        }
        m.appendTail(out);
        return out.toString();
    }

    private static String money(String number, String suffix, Bu bu) {
        double v = Double.parseDouble(number.replace(",", ""));
        String sfx = suffix == null ? "" : suffix.strip().toLowerCase();
        if (sfx.equals("k")) v *= 1_000;
        else if (sfx.equals("m") || sfx.equals("million")) v *= 1_000_000;
        return format(round(v * bu.perSgd()));
    }

    /** Whole units below 1,000; three significant figures above, so a converted figure does not pretend to be exact. */
    static double round(double v) {
        if (v < 1000) return Math.round(v);
        double step = Math.pow(10, Math.floor(Math.log10(v)) - 2);
        return Math.round(v / step) * step;
    }

    private static String format(double v) { return String.format("%,.0f", v); }
}
