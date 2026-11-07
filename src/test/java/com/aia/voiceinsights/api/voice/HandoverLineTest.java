package com.aia.voiceinsights.api.voice;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The phrase that ends a dictation gets a looser confidence bar, so it must be recognised even when the name is misheard, and only then. */
class HandoverLineTest {

    private static List<String> w(String s) {
        return List.of(s.toLowerCase().split("[^\\p{L}\\p{N}]+")).stream().filter(x -> !x.isEmpty()).toList();
    }

    @Test
    void recognisesTheHandoverEvenWhenTheNameIsMisheard() {
        for (String s : new String[] {"Spark, over to you.", "Psst, over to you.", "Park, over to you.", "Over to you.", "Okay Spark, your turn.", "Over to you, Spark."}) {
            assertTrue(AzureOpenAiRealtimeTranscriptionClient.isHandoverLine(w(s)), s);
        }
    }

    @Test
    void ordinaryDictationIsNotAHandover() {
        for (String s : new String[] {"He is worried about his medical bills.", "She said it is over to you and her husband to decide the plan today", ""}) {
            assertFalse(AzureOpenAiRealtimeTranscriptionClient.isHandoverLine(w(s)), s);
        }
    }
}
