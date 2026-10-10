package com.aia.voiceinsights.api.voice;

import com.ibm.icu.text.Transliterator;

/** Writes any Traditional Chinese in a transcript as Simplified, so one conversation never mixes the two. */
final class ChineseScript {

    private static final Transliterator TO_SIMPLIFIED = Transliterator.getInstance("Hant-Hans");

    private ChineseScript() {}

    static String toSimplified(String text) {
        if (text == null || text.codePoints().noneMatch(c -> Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN)) return text;
        synchronized (TO_SIMPLIFIED) { // a Transliterator is not thread-safe
            return TO_SIMPLIFIED.transliterate(text);
        }
    }
}
