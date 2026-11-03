package com.aia.voiceinsights.api.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The languages Spark works in, defined once. Everything that used to name the original four languages in a prompt, a filter or a
 * map reads from here, so adding a language is a new row here, a phrase set in {@link JunoPhrases} and the wording rules in {@link Wording}.
 *
 * Findings that shaped this (measured against the Azure deployment with six insurance sentences per language, speech made by Azure TTS):
 * <ul>
 *   <li>Thai, Filipino and Vietnamese are transcribed almost perfectly when the language is <b>left to the model</b> and a short prompt names the script.
 *       Passing a {@code language} code was no better and, for Thai, once made the model answer in Chinese. So only English sets a code.</li>
 *   <li>Azure rejects some codes outright ("Language code 'si' is not recognized"), another reason not to pass codes for the rest.</li>
 * </ul>
 */
public final class Languages {

    private Languages() {}

    /**
     * @param code         what the app and the browser use: en, zh, ms, ta, th, fil, vi
     * @param name         the language in English, as it is named in prompts
     * @param sttCode      the code sent to speech recognition, or null to let the model recognise the language (see above)
     * @param sttPrompt    context for speech recognition when a session is in this language (null: the generic one is used)
     * @param writingStyle how to write to a customer in this language (follow-up messages, proposals)
     * @param spaceless    the script has no spaces between words (affects how text is shown and counted)
     */
    public record Lang(String code, String name, String sttCode, String sttPrompt, String writingStyle, boolean spaceless) {}

    public static final String DEFAULT = "en";

    private static final Map<String, Lang> ALL = new LinkedHashMap<>();

    static {
        add(new Lang("en", "English", "en", null, "Plain, warm, natural English.", false));
        add(new Lang("zh", "Mandarin Chinese (Simplified)", null, null, "Simplified Chinese; always use the polite form 您.", true));
        add(new Lang("ms", "Malay (Bahasa Melayu)", null, null, "Natural, polite Bahasa Melayu as spoken in Singapore.", false));
        add(new Lang("ta", "Tamil", null, null, "Natural, polite spoken-standard Tamil as used in Singapore.", false));
        add(new Lang("th", "Thai", null,
                "บทสนทนาเกี่ยวกับประกันในสิงคโปร์ ผู้พูดพูดภาษาไทย อาจมีคำภาษาอังกฤษปนอยู่ เขียนเป็นอักษรไทย ชื่อที่อาจพบ: สมชาย สมศักดิ์ สมพงษ์ สมพร ศุภชัย สุชาติ สุรศักดิ์ ประเสริฐ วิชัย กิตติพงษ์ ณัฐพงษ์ อนุรักษ์ ธนากร วรวุฒิ พรทิพย์ ศิริพร กัญญา มาลี ทรงชัย",
                "Polite, natural Thai. Address the reader as คุณ. End sentences with the polite particles the sender would use: ค่ะ / คะ for a female "
                        + "sender, ครับ for a male sender (use ค่ะ / คะ when the sender's gender is not known). Never use blunt or overly formal "
                        + "bureaucratic Thai. Keep \"AIA\" in English.",
                true));
        add(new Lang("fil", "Filipino (Tagalog)", null,
                "Usapan tungkol sa insurance sa Singapore. Tagalog na may halong English (Taglish). Mga pangalang maaaring marinig: Dela Cruz, Marites, Villanueva, Bautista, Dizon, Aquino, Ocampo, Cristina.",
                "Natural, respectful Filipino (Tagalog) as people really speak it. Every sentence is Tagalog; English is only for product names, insurance "
                        + "terms such as \"premium\" or \"advisor\", and numbers. Never write a whole sentence, or a whole clause, in English. Show respect with "
                        + "\"po\" and \"opo\", and address the reader with the plural-respect forms \"kayo\", \"ninyo\" and \"inyong\". Keep \"AIA\" in English.",
                false));
        add(new Lang("vi", "Vietnamese", null,
                "Cuộc trò chuyện về bảo hiểm ở Singapore. Người nói tiếng Việt, có thể xen lẫn một vài từ tiếng Anh. Viết có dấu đầy đủ. Tên có thể gặp: Nguyễn, Trần, Phạm, Hoàng, Minh, Thảo, Linh, Phương, Hùng, Tuấn.",
                "Natural, polite Vietnamese with full diacritics. Address the reader as \"anh/chị\" and refer to the sender as \"tôi\" or \"chúng tôi\" "
                        + "(not \"em\", whose meaning depends on age). Use \"ạ\" sparingly for politeness. The insurance advisor is the \"tư vấn viên\". "
                        + "Keep \"AIA\" in English.",
                false));
    }

    private static void add(Lang l) { ALL.put(l.code(), l); }

    /** The languages an admin has turned on for the selected business unit, in registry order. Starts as all of them. */
    private static volatile List<String> enabled = List.copyOf(ALL.keySet());

    static void setEnabled(List<String> codes) {
        List<String> on = ALL.keySet().stream().filter(codes::contains).toList();
        enabled = on.isEmpty() ? List.of(DEFAULT) : on;
    }

    public static List<Lang> enabled() { return enabled.stream().map(ALL::get).toList(); }

    public static boolean isEnabled(String code) { return code != null && enabled.contains(code.toLowerCase()); }

    /** The language a conversation starts in when none is asked for: English when it is on, otherwise the first that is. */
    public static String fallback() { return enabled.contains(DEFAULT) ? DEFAULT : enabled.get(0); }

    /** Only the languages that are turned on are accepted; anything else is the fallback (English, unless an admin has turned it off). */
    public static String normalize(String code) {
        return isEnabled(code) ? code.toLowerCase() : fallback();
    }

    public static Lang of(String code) { return ALL.get(normalize(code)); }

    public static List<Lang> all() { return List.copyOf(ALL.values()); }

    public static boolean isSupported(String code) { return code != null && ALL.containsKey(code.toLowerCase()); }

    /** "English, Mandarin, Malay, Tamil, Thai, Filipino or Vietnamese": for prompts that say which languages a conversation may be in. */
    public static String promptList() {
        List<String> names = enabled().stream().map(l -> l.name().replaceAll(" \\(.*\\)", "").replace("Mandarin Chinese", "Mandarin")).toList();
        return names.size() == 1 ? names.get(0) : String.join(", ", names.subList(0, names.size() - 1)) + " or " + names.get(names.size() - 1);
    }

    /** Whether the transcript-repair step (written for Singapore English with Mandarin, Malay and Tamil words) should touch this language. It must not touch the others. */
    public static boolean repairable(String code) {
        String c = normalize(code);
        return "en".equals(c) || "zh".equals(c) || "ms".equals(c) || "ta".equals(c);
    }

    /**
     * Thai marks the speaker's gender on the polite particles (ค่ะ / คะ for a woman, ครับ for a man) and the first-person word. The fixed lines are
     * written with the female forms, so when Spark's voice is the male one they are switched, and the voice and the words agree.
     */
    public static String voiceAdapt(String lang, String text, boolean maleVoice) {
        if (text == null || !maleVoice || !"th".equals(normalize(lang))) return text;
        return text.replace("ค่ะ", "ครับ").replaceAll("คะ(?![\\u0E00-\\u0E7F])", "ครับ").replace("ดิฉัน", "ผม");
    }

    /**
     * Names that speech recognition tends to turn into other, more familiar names ("Somchai" heard as "Sanjay"). Handed to it as context so the
     * names of the customers in the languages that are on are spelled as spoken. Latin spellings: an English session writes them that way; a Thai
     * session also gets the Thai script in its own prompt.
     */
    private static final Map<String, String> NAMES = Map.of(
            "th", "Somchai, Somsak, Supachai, Prasert, Kittipong, Trongchai",
            "fil", "Dela Cruz, Marites, Villanueva, Bautista",
            "vi", "Nguyen, Tran, Pham, Hoang, Minh");

    /** The names above for the languages that are on, as one comma-separated list ("" when none of them is). */
    public static String nameHints() {
        return enabled.stream().map(NAMES::get).filter(java.util.Objects::nonNull).collect(java.util.stream.Collectors.joining(", "));
    }

    /**
     * Spark's prompts bound a line in English words ("up to 55 words"), which a language written without spaces cannot count. Spoken, Thai takes about
     * 11 characters a second and Mandarin about 4.5, so a Thai reading of 55 words ran to half a minute. This gives such a language its limits in
     * characters (about what 55 / 20 / 80 words come to, a little under); "" for the others.
     */
    public static String lengthNote(String code) {
        return switch (normalize(code)) {
            case "th" -> "LENGTH: write short lines. In Thai, count characters, not words: the first line after the dictation at most 150 characters, "
                    + "a question line at most 80, the wrap-up at most 200. Say the key facts only; leave out anything the advisor already knows.\n";
            case "zh" -> "LENGTH: write short lines. In Chinese, count characters, not words: the first line after the dictation at most 70 characters, "
                    + "a question line at most 35, the wrap-up at most 100.\n";
            case "fil", "vi" -> "LENGTH: write short lines: the first line after the dictation at most 45 words, a question line at most 18 words, the wrap-up at most 70 words.\n";
            default -> "";
        };
    }

    /** One line telling a model how to write in this language (English: none). For Spark's own lines, as for the follow-up and proposal writers. */
    public static String styleNote(String code) {
        return "en".equals(normalize(code)) ? "" : "STYLE: " + of(code).writingStyle() + "\n";
    }

    /** True if this letter's script is one a speaker of {@code lang} produces: Latin always, plus the language's own script. */
    public static boolean scriptAllowed(String lang, int codePoint) {
        Character.UnicodeScript s = Character.UnicodeScript.of(codePoint);
        if (s == Character.UnicodeScript.LATIN || s == Character.UnicodeScript.HAN || s == Character.UnicodeScript.TAMIL) return true;
        return "th".equals(normalize(lang)) && s == Character.UnicodeScript.THAI;
    }
}
