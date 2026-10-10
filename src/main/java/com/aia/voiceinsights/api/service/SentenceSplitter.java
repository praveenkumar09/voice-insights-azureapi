package com.aia.voiceinsights.api.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Cuts a streamed answer into sentences as the words arrive, so each sentence can be spoken while the next is still being
 * written. It never splits inside "Mr. Tan", "$8.5k", "e.g. this" or between a sentence and the source marker that follows it
 * ("…loan. [C2]"): at the end of the buffer it waits for the next character rather than guess.
 */
final class SentenceSplitter {

    private static final Set<String> ABBREVIATIONS = Set.of("mr", "mrs", "ms", "dr", "vs", "no", "st", "e.g", "i.e", "approx", "inc", "ltd", "pte");
    private static final int MAX_SENTENCE = 320;
    private static final int FIRST_CLAUSE_MIN = 42;   // an opening clause shorter than this is not worth speaking on its own   // a run-on is cut at a space rather than held back for ever

    private final StringBuilder buf = new StringBuilder();
    private boolean first = true;   // nothing has been handed over yet

    /** Adds text; returns every sentence that is now complete (usually none or one). */
    List<String> feed(String chunk) {
        List<String> out = new ArrayList<>();
        if (chunk == null || chunk.isEmpty()) return out;
        buf.append(chunk);
        int end;
        while ((end = boundary()) > 0) {
            String s = buf.substring(0, end).trim();
            buf.delete(0, end);
            if (!s.isEmpty()) { out.add(s); first = false; }
        }
        return out;
    }

    /** What is left when the answer has finished. */
    String flush() {
        String s = buf.toString().trim();
        buf.setLength(0);
        return s.isEmpty() ? null : s;
    }

    /** End (exclusive) of the first complete sentence in the buffer, or -1 when more text is needed. */
    private int boundary() {
        int len = buf.length();
        for (int i = 0; i < len; i++) {
            char c = buf.charAt(i);
            if (c == '\n') {
                if (buf.substring(0, i).isBlank()) continue;
                return i + 1;
            }
            if (!isTerminal(c)) continue;
            boolean cjk = c == '。' || c == '！' || c == '？';
            if (c == '.') {
                if (i + 1 >= len) return -1;                              // wait: is it "3.5" or the end of a sentence?
                if (Character.isDigit(buf.charAt(i + 1))) continue;       // 8.5
                String w = wordBefore(i);
                if (ABBREVIATIONS.contains(w.toLowerCase(Locale.ROOT)) || (w.length() == 1 && Character.isUpperCase(w.charAt(0)))) continue;
            }
            int j = i + 1;
            while (j < len && "\"')”’）」".indexOf(buf.charAt(j)) >= 0) j++;
            int k = j;
            while (k < len && buf.charAt(k) == ' ') k++;
            if (k >= len) return -1;                                      // wait for what follows (maybe a [C2] marker)
            if (buf.charAt(k) == '[') {                                   // the source markers belong to this sentence
                int close = buf.indexOf("]", k);
                if (close < 0) return -1;
                while (true) {                                            // "[C4][A1]": every marker in a row
                    int next = close + 1;
                    while (next < len && buf.charAt(next) == ' ') next++;
                    if (next >= len) return -1;                           // wait: another marker may follow
                    if (buf.charAt(next) != '[') break;
                    int c2 = buf.indexOf("]", next);
                    if (c2 < 0) return -1;
                    close = c2;
                }
                return close + 1;
            }
            if (!cjk && k == j) continue;                                 // no space after the stop: not a boundary
            if (!cjk && Character.isLowerCase(buf.charAt(k))) continue;   // "e.g. foo", "vs. bar"
            return j;
        }
        // The first thing said is what the advisor waits for: if the opening sentence is long, hand over its first clause
        // as soon as there is one, so speech can begin while the rest is still being written.
        if (first && len > FIRST_CLAUSE_MIN + 12) {
            int best = -1;
            for (String d : new String[] {", ", " — ", "; ", ": "}) {
                int at = buf.lastIndexOf(d, len - d.length() - 2);
                if (at >= FIRST_CLAUSE_MIN && at > best && buf.substring(0, at).chars().filter(c -> c == '[').count() == buf.substring(0, at).chars().filter(c -> c == ']').count()) best = at + d.length() - (d.endsWith(" ") ? 1 : 0);
            }
            if (best > 0) return best;
        }
        if (len > MAX_SENTENCE) {
            int cut = buf.lastIndexOf(" ", MAX_SENTENCE - 80);
            return cut > 0 ? cut + 1 : -1;
        }
        return -1;
    }

    private static boolean isTerminal(char c) {
        return c == '.' || c == '!' || c == '?' || c == '。' || c == '！' || c == '？';
    }

    private String wordBefore(int dot) {
        int s = dot;
        while (s > 0 && (Character.isLetter(buf.charAt(s - 1)) || buf.charAt(s - 1) == '.')) s--;
        return buf.substring(s, dot);
    }
}
