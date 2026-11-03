package com.aia.voiceinsights.api.service;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class MultilingualTest {

    private static final List<String> NEW = List.of("th", "fil", "vi");
    private static final Pattern PLACEHOLDER = Pattern.compile("(?i)todo|tbd|lorem|\\?\\?\\?|\\{\\w+}");

    private static List<String> allText(JunoPhrases.Phrases p) {
        return Stream.concat(Stream.of(p.greeting(), p.declined(), p.unclear(), p.firstQuestion(), p.closing(), p.wrap(), p.askAway(), p.tellMore(),
                p.noFigures(), p.nudge(), p.missed(), p.anythingElse(), p.yes(), p.no(), p.yesText(), p.noText()), p.acks().stream()).toList();
    }

    // ── the registry ──

    @Test
    void everyRegisteredLanguageHasAPhraseSetAndEveryPhraseSetIsRegistered() {
        for (Languages.Lang l : Languages.all()) assertNotNull(JunoPhrases.of(l.code()), l.code());
        assertEquals(Languages.all().stream().map(Languages.Lang::code).collect(java.util.stream.Collectors.toSet()), JunoPhrases.codes());
    }

    @Test
    void theNewLanguagesAreSupportedAndUnknownOnesFallBackToEnglish() {
        for (String c : NEW) assertEquals(c, Languages.normalize(c.toUpperCase()));
        assertEquals("en", Languages.normalize("si"));      // Sinhala is deliberately not supported
        assertEquals("en", Languages.normalize(null));
        assertEquals("en", JunoPhrases.normalize("xx"));
    }

    @Test
    void thePromptListNamesEveryLanguage() {
        assertEquals("English, Mandarin, Malay, Tamil, Thai, Filipino or Vietnamese", Languages.promptList());
    }

    @Test
    void noPromptStillSaysOnlyTheOriginalFourLanguages() throws Exception {
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            for (Path f : files.filter(x -> x.toString().endsWith(".java")).toList()) {
                assertFalse(Files.readString(f).contains("English, Mandarin, Malay or Tamil"), f + " still lists only four languages; use the registry");
            }
        }
    }

    // ── the fixed lines ──

    @Test
    void everyFixedLineIsWrittenForEveryNewLanguage() {
        JunoPhrases.Phrases en = JunoPhrases.of("en");
        for (String c : NEW) {
            JunoPhrases.Phrases p = JunoPhrases.of(c);
            assertEquals(allText(en).size(), allText(p).size(), c + ": same number of lines as English");
            allText(p).forEach(t -> {
                assertFalse(t == null || t.isBlank(), c + ": a line is empty");
                assertFalse(PLACEHOLDER.matcher(t).find(), c + ": placeholder text in \"" + t + "\"");
            });
            assertEquals(en.acks().size(), p.acks().size(), c + ": acknowledgements");
        }
    }

    @Test
    void theNewLinesAreInTheirOwnScriptNotEnglishLeftOver() {
        String latinOnly = "[\\p{IsLatin}\\p{N}\\p{P}\\p{Z}]*";
        assertTrue(JunoPhrases.of("th").greeting().codePoints().anyMatch(c -> Character.UnicodeScript.of(c) == Character.UnicodeScript.THAI));
        assertFalse(JunoPhrases.of("th").greeting().matches(latinOnly));
        assertNotEquals(JunoPhrases.of("en").greeting(), JunoPhrases.of("fil").greeting());
        assertNotEquals(JunoPhrases.of("en").greeting(), JunoPhrases.of("vi").greeting());
        assertTrue(JunoPhrases.of("vi").greeting().matches(".*[ăâđêôơưạảấầẩẫậắằẳẵặẹẻẽếềểễệỉịọỏốồổỗộớờởỡợụủứừửữựỳỵỷỹ].*"), "Vietnamese diacritics are present");
    }

    @Test
    void theConsentLinesAskForPermissionAndNameTheBrand() {
        for (String c : NEW) {
            assertTrue(JunoPhrases.of(c).greeting().contains("AIA"), c + ": greeting names AIA");
            assertTrue(JunoPhrases.of(c).greeting().contains("Spark"), c + ": greeting names Spark");
        }
        assertTrue(JunoPhrases.of("fil").greeting().contains("po"), "Filipino greeting is respectful");
    }

    @Test
    void noNewLineUsesTheWordsRecommendOrItsTranslations() {
        Pattern banned = Pattern.compile("(?i)recommend|rekomend|irekomenda|khuyến nghị|คำแนะนำ");
        for (String c : NEW) allText(JunoPhrases.of(c)).forEach(t -> assertFalse(banned.matcher(t).find(), c + ": \"" + t + "\" says recommend instead of suggest"));
    }

    // ── Thai speaks as a man or a woman ──

    @Test
    void thaiParticlesFollowTheVoice() {
        String female = "ได้เลยค่ะ ดิฉันกำลังฟังอยู่ค่ะ ได้ไหมคะ";
        assertEquals("ได้เลยครับ ผมกำลังฟังอยู่ครับ ได้ไหมครับ", Languages.voiceAdapt("th", female, true));
        assertEquals(female, Languages.voiceAdapt("th", female, false));
        assertEquals("ค่ะ", Languages.voiceAdapt("en", "ค่ะ", true), "only Thai is adapted");
        assertEquals("คะแนนสอบ", Languages.voiceAdapt("th", "คะแนนสอบ", true), "คะ inside a word (คะแนน, score) is left alone");
    }

    // ── what the speech filter lets through ──

    @Test
    void thaiScriptIsAllowedInAThaiSessionOnly() throws Exception {
        int thai = "ก".codePointAt(0), latin = 'a', tamil = "த".codePointAt(0), arabic = "ع".codePointAt(0), sinhala = "අ".codePointAt(0);
        assertTrue(Languages.scriptAllowed("th", thai));
        assertFalse(Languages.scriptAllowed("en", thai));
        assertFalse(Languages.scriptAllowed("fil", thai));
        for (String l : List.of("en", "th", "fil", "vi")) { assertTrue(Languages.scriptAllowed(l, latin)); assertTrue(Languages.scriptAllowed(l, tamil)); assertFalse(Languages.scriptAllowed(l, arabic)); }
        assertFalse(Languages.scriptAllowed("th", sinhala));
    }

    @Test
    void theRepairStepOnlyTouchesTheLanguagesItWasWrittenFor() {
        for (String c : List.of("en", "zh", "ms", "ta")) assertTrue(Languages.repairable(c), c);
        for (String c : NEW) assertFalse(Languages.repairable(c), c);
    }

    // ── wording: the app suggests, it never recommends ──

    @Test
    void relevanceIsSaidInWordsInEveryLanguage() {
        for (String c : NEW) {
            String hi = Wording.relevance(90, c), mid = Wording.relevance(60, c), lo = Wording.relevance(20, c);
            assertEquals(3, java.util.Set.of(hi, mid, lo).size(), c + ": three different labels");
            assertNotEquals(Wording.relevance(90, "en"), hi, c + ": not the English label");
        }
        assertEquals("Rất liên quan", Wording.relevance(90, "vi"));
        assertEquals("Kaugnay", Wording.relevance(60, "fil"));
        assertEquals("เกี่ยวข้องมาก", Wording.relevance(80, "th"));
    }

    @Test
    void recommendIsRewrittenAsSuggestInTheNewLanguages() {
        assertEquals("Ito ang aming mungkahi para sa inyo.", Wording.suggest("Ito ang aming rekomendasyon para sa inyo."));
        assertEquals("Iminumungkahi po namin ang planong ito.", Wording.suggest("Inirerekomenda po namin ang planong ito.").replace("Iminumungkahi po namin", "Iminumungkahi po namin"));
        assertEquals("Đây là gợi ý của chúng tôi.", Wording.suggest("Đây là khuyến nghị của chúng tôi."));
        assertEquals("นี่คือข้อเสนอแนะของเรา", Wording.suggest("นี่คือคำแนะนำของเรา"));
    }

    @Test
    void theHouseRuleTellsTheModelTheRightWordsInEachLanguage() {
        String rule = Wording.PROMPT_RULE;
        assertTrue(rule.contains("ข้อเสนอแนะ") && rule.contains("mungkahi") && rule.contains("gợi ý"));
    }

    // ── the old four are untouched ──

    @Test
    void theOriginalLanguagesStillWork() {
        assertEquals("Highly relevant", Wording.relevance(90, "en"));
        assertEquals("高度相关", Wording.relevance(90, "zh"));
        assertEquals("建议", Wording.suggest("推荐"));
    }
}
