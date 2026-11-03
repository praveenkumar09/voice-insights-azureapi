package com.aia.voiceinsights.api.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BusinessUnitTest {

    @AfterEach
    void reset() {
        BusinessUnits.setCurrent("sg");
        Languages.setEnabled(Languages.all().stream().map(Languages.Lang::code).toList());
    }

    @Test
    void singaporeIsLeftExactlyAsItWas() {
        String p = "You are Spark, AIA Singapore's assistant in Singapore. Cover is SGD 100,000 or S$80k.";
        assertEquals(p, BusinessUnits.localize(p));
        assertEquals(p, BusinessUnits.convertAmounts(p));
        assertEquals(p, LocalizingChatModel.localized(new Prompt(List.of(new SystemMessage(p)))).getInstructions().get(0).getText());
    }

    @Test
    void everyUnitHasWhatTheAppNeeds() {
        assertEquals(8, BusinessUnits.all().size());
        for (var b : BusinessUnits.all()) {
            assertTrue(b.name().startsWith("AIA "), b.id());
            assertTrue(b.perSgd() > 0, b.id());
            assertEquals(3, b.currency().length(), b.id());
            assertDoesNotThrow(() -> java.time.ZoneId.of(b.zone()), b.id());
            assertFalse(b.defaultLanguages().isEmpty(), b.id());
            assertTrue(b.defaultLanguages().stream().allMatch(Languages::isSupported), b.id());
            assertTrue(b.sttContext().length() < 400, b.id());
            assertFalse(b.id().equals("sg") ? false : b.sttContext().contains("Singapore"), b.id() + " speech context must not name Singapore");
        }
    }

    @Test
    void promptsNameTheSelectedUnit() {
        BusinessUnits.setCurrent("th");
        String t = BusinessUnits.localize("You are the Need Agent in AIA Singapore's insurance team for a customer in Singapore.");
        assertEquals("You are the Need Agent in AIA Thailand's insurance team for a customer in Thailand.", t);
        BusinessUnits.setCurrent("ph");
        assertTrue(BusinessUnits.localize("customers in Singapore").contains("the Philippines"));
        BusinessUnits.setCurrent("hk");
        assertTrue(BusinessUnits.localize("AIA Singapore").equals("AIA Group (Hong Kong)"));
    }

    @Test
    void amountsAreConvertedToTheUnitsCurrency() {
        BusinessUnits.setCurrent("th");
        assertEquals("Cover of THB 2,600,000.", BusinessUnits.convertAmounts("Cover of SGD 100,000."));
        assertEquals("THB 2,600,000", BusinessUnits.convertAmounts("S$100,000"));
        assertEquals("THB 2,600,000", BusinessUnits.convertAmounts("S$100k"));
        assertEquals("about THB 26,000,000", BusinessUnits.convertAmounts("about SGD 1 million"));
        assertEquals("THB 2,080–3,900/month", BusinessUnits.convertAmounts("SGD 80-150/month"));
        assertEquals("THB 2,080 to 3,900", BusinessUnits.convertAmounts("SGD 80 to 150"));
        assertEquals("a premium of THB 3,200", BusinessUnits.convertAmounts("a premium of SGD 123"));
        assertEquals("Income of ฿50,000", BusinessUnits.convertAmounts("Income of ฿50,000"));
        assertEquals("THB 2,600,000 and US$100,000", BusinessUnits.convertAmounts("$100,000 and US$100,000"), "a bare $ is SGD in the catalogue; US$ is not");
        BusinessUnits.setCurrent("vn");
        assertEquals("VND 1,950,000,000", BusinessUnits.convertAmounts("SGD 100,000"));
        BusinessUnits.setCurrent("my");
        assertEquals("MYR 330,000", BusinessUnits.convertAmounts("SGD 100,000"));
    }

    @Test
    void theModelIsToldTheCurrency() {
        BusinessUnits.setCurrent("hk");
        var p = LocalizingChatModel.localized(new Prompt(List.of(new SystemMessage("You help AIA Singapore advisors."), new UserMessage("Sum assured SGD 200,000"))));
        String sys = p.getInstructions().get(0).getText();
        assertTrue(sys.startsWith("You help AIA Group (Hong Kong) advisors."));
        assertTrue(sys.contains("HKD") && sys.contains("Never write SGD"));
        assertEquals("Sum assured HKD 1,180,000", p.getInstructions().get(1).getText());
    }

    @Test
    void onlyTheEnabledLanguagesAreAccepted() {
        Languages.setEnabled(List.of("en", "th", "vi"));
        assertEquals(List.of("en", "th", "vi"), Languages.enabled().stream().map(Languages.Lang::code).toList());
        assertEquals("th", Languages.normalize("TH"));
        assertEquals("en", Languages.normalize("ta"), "a language that is off becomes English");
        assertEquals("English, Thai or Vietnamese", Languages.promptList());
        assertEquals("th", JunoPhrases.normalize("th"));
        assertEquals("English", JunoPhrases.of("ta").language());
    }

    @Test
    void withoutEnglishTheFirstLanguageIsTheFallback() {
        Languages.setEnabled(List.of("vi", "th"));
        assertEquals("th", Languages.fallback(), "registry order: th before vi");
        assertEquals("th", Languages.normalize("en"));
        Languages.setEnabled(List.of());
        assertEquals(List.of("en"), Languages.enabled().stream().map(Languages.Lang::code).toList(), "never none");
    }

    @Test
    void phrasesNameTheSelectedUnit() {
        BusinessUnits.setCurrent("vn");
        assertTrue(JunoPhrases.of("en").greeting().contains("AIA Vietnam"));
        assertFalse(JunoPhrases.of("en").greeting().contains("Singapore"));
        for (String l : JunoPhrases.codes()) {
            String g = JunoPhrases.of(l).greeting();
            assertTrue(g.contains("AIA Vietnam") || g.contains("AIA Vietnam-இன்"), l + ": " + g);
        }
    }

    @Test
    void theVoiceHintNamesTheCountry() {
        BusinessUnits.setCurrent("th");
        Languages.setEnabled(List.of("en", "th"));
        String h = com.aia.voiceinsights.api.voice.VoiceWebSocketHandler.mixedLanguageHint();
        assertEquals("English as spoken in Thailand with a few Thai words mixed in. Write each word in the language it was spoken in, and never translate.", h);
        BusinessUnits.setCurrent("sg");
        assertTrue(com.aia.voiceinsights.api.voice.VoiceWebSocketHandler.mixedLanguageHint().startsWith("Singapore English"));
    }
}
