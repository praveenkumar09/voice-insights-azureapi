package com.aia.voiceinsights.api.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Corrects what the speech model cannot know from one short segment: a Mandarin word it wrote as sounds or as an English word
 * ("tuē xiāo", "new er"), a Malay or Tamil word it turned into an English one ("history" for isteri), and 她 / 他 (the same sound,
 * told apart only by who is being talked about). A segment is transcribed alone, so the words around it are given to a small model
 * together with it.
 *
 * <p>It may only change words, never add, drop or reorder what was said: an answer that differs from the original by more than a
 * few words, that is empty, or that arrives late is ignored and the original is kept. Ordinary English passes through unchanged.
 */
@Service
public class TranscriptRepairService {

    private static final Logger log = LoggerFactory.getLogger(TranscriptRepairService.class);
    private static final long BUDGET_MS = 3000;
    private static final int CONTEXT_CHARS = 700;
    private static final ObjectMapper JSON = new ObjectMapper();

    static final String SYSTEM = """
            You correct speech-to-text errors in one short segment of an insurance advisor's dictation. The advisor speaks Singapore English and mixes in single Mandarin, Malay, Hokkien or Tamil words. The speech model hears each segment alone, so it sometimes gets those words wrong. You get EARLIER text (final, for context only) and the SEGMENT.

            DEFAULT: return the SEGMENT exactly as it is. Words already in Mandarin, Malay, Hokkien or Tamil are CORRECT and must never be translated, replaced or "normalised" (isteri, anak, anak pertama, tolong jelaskan, amma, appa, pillai, romba kashtam, kiasu, lah, 女儿, 退休 all stay as they are).

            Change a word ONLY in these cases:
            1. It is Mandarin written as romanised sounds (pinyin, with or without tone marks, e.g. "tuē xiāo", "tuaishao") or as English words that sound like it ("new er" for 女儿): write it in Simplified Chinese characters, judged from the meaning around it.
            2. It is a Malay or Tamil word the model wrote as an unrelated English word that makes no sense there ("history" for isteri, "the sea" for isteri, "ama" or "alma" for amma): write the Malay/Tamil word in Latin letters.
            3. The Chinese pronouns 她 / 他 / 它 only (English he, she, his, her are never changed): choose from who the EARLIER text says is being talked about (Mrs, she, wife, daughter, mother → 她; Mr, he, husband, son, father → 他). If it says nothing, leave it.

            Never translate, summarise, add, remove or reorder anything. Do not touch grammar, spelling, punctuation, numbers or Singlish. Put a space between Chinese characters and neighbouring English words exactly as the segment has them (add none, remove none).
            Reply as JSON: {"text": "<the segment>"}.
            """;

    private final HedgedChat chat;

    public TranscriptRepairService(HedgedChat chat) {
        this.chat = chat;
    }

    /** @param earlier the transcript so far (may be empty); @param segment the new final text. @return the segment, corrected if it needed it. */
    public String repair(String earlier, String segment) {
        if (segment == null || segment.strip().length() < 3) return segment;
        String ctx = earlier == null ? "" : earlier.strip();
        if (ctx.length() > CONTEXT_CHARS) ctx = ctx.substring(ctx.length() - CONTEXT_CHARS);
        String user = "EARLIER:\n" + (ctx.isEmpty() ? "(nothing yet)" : ctx) + "\n\nSEGMENT:\n" + segment;
        long t0 = System.currentTimeMillis();
        try {
            String raw = CompletableFuture.supplyAsync(() -> chat.json(SYSTEM, user, 0.0, 400, 1500)).get(BUDGET_MS, TimeUnit.MILLISECONDS);
            String fixed = JSON.readTree(raw).path("text").asText("").strip();
            if (fixed.isEmpty() || fixed.equals(segment.strip())) return segment;
            fixed = spaced(segment, fixed);
            if (!plausible(segment, fixed)) {
                log.info("transcript repair ignored (changed too much): '{}' -> '{}'", segment, fixed);
                return segment;
            }
            log.info("transcript repair ({} ms): '{}' -> '{}'", System.currentTimeMillis() - t0, segment, fixed);
            return fixed;
        } catch (Exception e) {
            log.info("transcript repair skipped ({} ms): {}", System.currentTimeMillis() - t0, e.toString());
            return segment;
        }
    }

    /** The model sometimes glues a Chinese word to the English next to it ("about退休"); keep the spacing the segment had. */
    static String spaced(String before, String after) {
        if (TIGHT.matcher(before).find()) return after;
        return after.replaceAll("([A-Za-z0-9])(\\p{IsHan})", "$1 $2").replaceAll("(\\p{IsHan})([A-Za-z0-9])", "$1 $2");
    }

    private static final java.util.regex.Pattern TIGHT = java.util.regex.Pattern.compile("([A-Za-z0-9])(\\p{IsHan})|(\\p{IsHan})([A-Za-z0-9])");

    /** At most a third of the units (words, or single Chinese characters) may differ, so a repair can swap words but not rewrite. */
    static boolean plausible(String before, String after) {
        Set<String> a = units(before), b = units(after);
        if (a.isEmpty()) return true;
        int common = 0;
        for (String u : a) if (b.contains(u)) common++;
        int total = Math.max(a.size(), b.size());
        return common * 3 >= total * 2 && after.length() <= before.length() * 1.5 + 8 && after.length() >= before.length() * 0.5 - 4;
    }

    private static Set<String> units(String s) {
        Set<String> out = new HashSet<>();
        StringBuilder w = new StringBuilder();
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            if (Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN) {
                if (w.length() > 0) { out.add(w.toString()); w.setLength(0); }
                out.add(new String(Character.toChars(cp)));
            } else if (Character.isLetterOrDigit(cp)) {
                w.appendCodePoint(Character.toLowerCase(cp));
            } else if (w.length() > 0) {
                out.add(w.toString()); w.setLength(0);
            }
        }
        if (w.length() > 0) out.add(w.toString());
        return out;
    }
}
