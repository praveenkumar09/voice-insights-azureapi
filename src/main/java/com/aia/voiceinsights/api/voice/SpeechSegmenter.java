package com.aia.voiceinsights.api.voice;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Cuts a live stream of 24kHz, 16-bit mono audio into segments that each go to the speech model as one piece, and decides
 * which sounds are speech at all.
 *
 * <p><b>Where it cuts decides whether words survive.</b> A cut through a word loses it (the model drops the half word at the
 * end of one segment and mishears the start of the next), so:
 * <ul>
 *   <li><b>A pause is judged against the speaker, not against a fixed number.</b> In a real room the "silence" between
 *       sentences is not quiet at the microphone (reverb, fans, speakers): measured, it sat at 400-700 on the 16-bit scale
 *       where the old fixed limit was ~120, so no pause was ever found and segments were cut blindly through words.</li>
 *   <li><b>Cut in the middle of a pause</b>, keeping a little quiet on both sides. Only a real pause (0.3 s) is a place to
 *       cut: a shorter dip splits a phrase and leaves a tiny tail the model cannot read.</li>
 *   <li><b>Cutting inside speech is a last resort</b> (after 20 s without a pause). Then the quietest moment of the last
 *       4 s is used, and the next segment starts 1 s <i>before</i> the cut so that any word the model drops at an edge is
 *       heard whole in the middle of the next one ({@link Segment#overlap()}; the caller removes the repeated words).</li>
 *   <li><b>Audio is held and sent only at a cut</b>, so the cut point can be chosen looking backwards (the speech model
 *       transcribes nothing before a cut anyway, so this adds no delay).</li>
 *   <li>A flush (the speaker paused or finished) sends everything up to the last word, with silence after it so the last
 *       word is not clipped. The noise between the last word and the button press is not sent.</li>
 * </ul>
 *
 * <p><b>What counts as speech.</b> Only sound that stays above the pause level for at least 120 ms ("voiced"); scattered
 * blips and reverb tails do not add up to speech. For a single speaker dictating, an <i>isolated</i> short sound (nothing
 * said in the 3 s before it) needs 0.7 s of voiced sound, and a segment far quieter than the speaker has been (a TV, another
 * room, a passer-by) is dropped. A short last word right after speech is kept.
 *
 * <p>Not thread-safe: the caller serialises access.
 */
final class SpeechSegmenter {

    /**
     * One piece of audio ready for the speech model. {@code overlap}: it begins with audio already sent in the previous
     * segment, so the first words of its transcript may repeat the end of the previous one.
     */
    record Segment(byte[] audio, int speechMs, long startMs, long endMs, boolean overlap) {}

    static final int FRAME_SAMPLES = 480;                    // 20 ms at 24 kHz
    static final int FRAME_BYTES = FRAME_SAMPLES * 2;
    static final int FRAME_MS = 20;
    private static final int LEVEL_FRAMES = 5;               // a level is the loudness of the last 100 ms
    private static final int FLOOR_WINDOW_FRAMES = 150;      // the room's noise level is the quietest 100 ms of the last 3 s
    private static final int VOICED_RUN_FRAMES = 6;          // sound must stay up this long (120 ms) to count as speech

    private static final double QUIET_OF_FLOOR = 2.0;        // quiet = below this many times the noise floor...
    private static final double QUIET_OF_SPEECH = 0.30;      // ...and below this share of the speaker's recent level
    private static final double SPEECH_OVER_FLOOR = 2.5;     // a frame counts toward the speaker's level above this
    private static final double MIN_QUIET_LEVEL = 60;        // never treat anything under this (16-bit units) as speech
    private static final double MIN_SPEECH_LEVEL = 150;      // ...and nothing under this teaches us the speaker's level

    private static final int LEAD_MS = 140;                  // quiet kept before speech in a segment
    private static final int TAIL_MS = 160;                  // quiet kept after speech in a segment
    private static final int PAD_MS = 260;                   // silence appended to a flushed segment
    private static final int OVERLAP_MS = 1000;              // how far before a forced cut the next segment starts

    private static final int MIN_TURN_MS = 2000;             // do not cut sooner than this
    private static final int MAX_TURN_MS = 20000;            // past this, a forced cut at the quietest moment
    private static final int LOOKBACK_MS = 4000;
    private static final int ISOLATED_MS = 3000;             // nothing voiced this long before = an isolated sound
    private static final int ISOLATED_MIN_MS = 700;          // voiced sound an isolated segment needs (single-speaker dictation)
    private static final double BACKGROUND_OF_SESSION = 0.25;      // a segment this much quieter than the speaker is background...
    private static final double BACKGROUND_OF_SESSION_ISOLATED = 0.40; // ...or this much if it also stands alone
    private static final int SESSION_REF_MIN_MS = 3000;      // speech needed before the speaker's level is trusted

    // ── tunable ──
    private volatile int cutGapMs = 300;                     // a pause this long is a place to cut once the segment is long enough
    private volatile int minSpeechMs = 350;                  // less voiced sound than this is not a segment
    private volatile int flushMinSpeechMs = 200;             // ...except a short last word right after speech
    private volatile boolean conversational;
    private volatile boolean singleSpeaker;

    /** Conversation with Juno: each answer is ended by the browser, so slicing at short pauses would split it. */
    void setConversational(boolean on) {
        conversational = on;
        cutGapMs = on ? 550 : 300;
        minSpeechMs = on ? 250 : 350;   // a one-word "No." is about a quarter of a second of speech
        flushMinSpeechMs = on ? 150 : 200;
    }

    /** One person talking (an advisor dictating): isolated short sounds and much quieter sounds are not them. */
    void setSingleSpeaker(boolean on) {
        singleSpeaker = on;
    }

    // ── held audio ──
    private final ByteArrayOutputStream carry = new ByteArrayOutputStream(); // a partial frame between chunks
    private final List<byte[]> frames = new ArrayList<>();
    private final List<Double> energy = new ArrayList<>();                   // mean square of each held frame
    private final List<Double> levelOf = new ArrayList<>();                  // the 100 ms level at each held frame
    private final List<Boolean> quiet = new ArrayList<>();
    private final List<Boolean> voiced = new ArrayList<>();
    private long receivedFrames;                                             // every frame ever fed
    private long heldStartFrame;                                             // stream position of frames.get(0)
    private int runLen;                                                      // current run of non-quiet frames

    // ── what we know about the room and the speaker ──
    private final double[] levels = new double[FLOOR_WINDOW_FRAMES];         // recent 100 ms levels, a ring
    private int levelCount, levelPos;
    private double speechRef;                                                // the speaker's recent level (for pauses)
    private double sessionLevel;                                             // the speaker's level over the session (for background)
    private long sessionVoicedMs;
    private long prevVoicedEndFrame = -1;                                    // stream position of the last voiced frame sent
    private boolean carryOverlap;                                            // the next segment starts with audio already sent

    private long lastVoicedGlobal = -1;                                      // stream position of the latest voiced frame
    private int voicedSinceEnd;                                              // voiced frames since the last end of an utterance

    private final List<String> notes = new ArrayList<>();

    /**
     * True once, when the speaker has said something (at least {@code minVoicedMs} of it) and then been quiet for
     * {@code quietMs}: the end of a whole utterance, as opposed to a pause inside it. Judged like every pause here: against the
     * speaker, so a noisy room does not hide it and a soft word does not fake it.
     */
    boolean utteranceEnded(int quietMs, int minVoicedMs) {
        if (voicedSinceEnd * FRAME_MS < minVoicedMs) return false;
        long quiet = (receivedFrames - 1 - lastVoicedGlobal) * FRAME_MS;
        if (quiet < quietMs) return false;
        voicedSinceEnd = 0;
        return true;
    }

    /** Why something was dropped since the last call (for the log). */
    List<String> drainNotes() {
        List<String> n = new ArrayList<>(notes);
        notes.clear();
        return n;
    }

    /** Feeds a chunk of audio; returns the segments that are now complete (usually none, sometimes one). */
    List<Segment> feed(byte[] pcm16) {
        List<Segment> out = new ArrayList<>();
        carry.write(pcm16, 0, pcm16.length);
        byte[] all = carry.toByteArray();
        carry.reset();
        int full = all.length / FRAME_BYTES * FRAME_BYTES;
        for (int off = 0; off < full; off += FRAME_BYTES) {
            byte[] f = new byte[FRAME_BYTES];
            System.arraycopy(all, off, f, 0, FRAME_BYTES);
            addFrame(f);
            Segment s = maybeCut();
            if (s != null) out.add(s);
        }
        if (full < all.length) carry.write(all, full, all.length - full);
        return out;
    }

    /**
     * The speaker paused or finished: everything up to their last word goes out as one segment (with silence after it so the
     * last word is not clipped). The noise after the last word is dropped. Returns null when there is nothing worth sending.
     */
    Segment flush() {
        carry.reset(); // under 20 ms of leftover: nothing to say
        if (frames.isEmpty()) return null;
        int last = lastVoiced();
        if (last < 0) {
            clearHeld();
            return null;
        }
        int end = Math.min(frames.size(), last + 1 + TAIL_MS / FRAME_MS);
        Segment s = build(end, true);
        clearHeld();
        return s;
    }

    // ── analysis ──

    private void addFrame(byte[] f) {
        double sum = 0;
        for (int i = 0; i < FRAME_SAMPLES; i++) {
            short v = (short) ((f[2 * i] & 0xff) | (f[2 * i + 1] << 8));
            sum += (double) v * v;
        }
        if (frames.isEmpty()) heldStartFrame = receivedFrames;
        receivedFrames++;
        frames.add(f);
        energy.add(sum / FRAME_SAMPLES);

        double level = levelAt(frames.size() - 1);
        levelOf.add(level);
        levels[levelPos] = level;
        levelPos = (levelPos + 1) % FLOOR_WINDOW_FRAMES;
        if (levelCount < FLOOR_WINDOW_FRAMES) levelCount++;

        double floor = floor();
        if (level > Math.max(SPEECH_OVER_FLOOR * floor, MIN_SPEECH_LEVEL)) {
            speechRef = speechRef == 0 ? level : speechRef + 0.02 * (level - speechRef);
        }
        boolean q = level < Math.max(Math.max(QUIET_OF_FLOOR * floor, QUIET_OF_SPEECH * speechRef), MIN_QUIET_LEVEL);
        quiet.add(q);

        // Sound counts as speech once it has stayed up for VOICED_RUN_FRAMES; the frames that led up to it count too.
        voiced.add(false);
        if (q) {
            runLen = 0;
        } else {
            runLen++;
            int n = voiced.size();
            if (runLen == VOICED_RUN_FRAMES) {
                for (int k = n - VOICED_RUN_FRAMES; k < n; k++) voiced.set(k, true);
                voicedSinceEnd += VOICED_RUN_FRAMES;
                lastVoicedGlobal = receivedFrames - 1;
            } else if (runLen > VOICED_RUN_FRAMES) {
                voiced.set(n - 1, true);
                voicedSinceEnd++;
                lastVoicedGlobal = receivedFrames - 1;
            }
        }
    }

    /** Loudness (rms) of the 100 ms ending at held frame {@code i}. */
    private double levelAt(int i) {
        int from = Math.max(0, i - LEVEL_FRAMES + 1);
        double sum = 0;
        for (int k = from; k <= i; k++) sum += energy.get(k);
        return Math.sqrt(sum / (i - from + 1));
    }

    private double floor() {
        double min = Double.MAX_VALUE;
        for (int i = 0; i < levelCount; i++) if (levels[i] < min) min = levels[i];
        return levelCount == 0 ? 0 : min;
    }

    private int voicedMs(int from, int to) {
        int n = 0;
        for (int i = from; i < to; i++) if (voiced.get(i)) n++;
        return n * FRAME_MS;
    }

    private int firstVoiced() {
        for (int i = 0; i < voiced.size(); i++) if (voiced.get(i)) return i;
        return -1;
    }

    private int lastVoiced() {
        for (int i = voiced.size() - 1; i >= 0; i--) if (voiced.get(i)) return i;
        return -1;
    }

    private int trailingQuietFrames() {
        int n = 0;
        for (int i = quiet.size() - 1; i >= 0 && quiet.get(i); i--) n++;
        return n;
    }

    // ── cutting ──

    private Segment maybeCut() {
        int size = frames.size();
        int turnMs = size * FRAME_MS;
        int voicedNow = voicedMs(0, size);

        // Nothing voiced so far: keep only a little lead-in, never a growing run of quiet or blips.
        if (voicedNow == 0) {
            if (size > 150) dropFront(size - 50);
            else trimLeadingQuiet(LEAD_MS / FRAME_MS);
            return null;
        }

        int quietRun = trailingQuietFrames();
        int quietMs = quietRun * FRAME_MS;
        if (turnMs >= MIN_TURN_MS && quietMs >= cutGapMs) {
            // Cut in the pause, keeping TAIL_MS of it with this segment; the rest of the pause starts the next one.
            int end = Math.min(size, size - quietRun + Math.max(1, Math.min(quietRun, TAIL_MS / FRAME_MS)));
            Segment s = build(end, false);
            removeFront(end);
            trimLeadingQuiet(LEAD_MS / FRAME_MS);
            return s;
        }

        if (turnMs >= MAX_TURN_MS) {
            int end = quietestPoint();
            Segment s = build(end, false);
            // Forced cut inside speech: the next segment starts OVERLAP_MS earlier, so a word dropped at this edge is whole in it.
            removeFront(Math.max(1, end - OVERLAP_MS / FRAME_MS));
            if (s != null) carryOverlap = true;
            return s;
        }
        return null;
    }

    /** The middle of the quietest 100 ms in the last LOOKBACK_MS: the least harmful place to cut a segment that ran long. */
    private int quietestPoint() {
        int size = frames.size();
        int from = Math.max(LEVEL_FRAMES, size - LOOKBACK_MS / FRAME_MS);
        int best = size - LEVEL_FRAMES / 2 - 1;
        double bestLevel = Double.MAX_VALUE;
        for (int end = from; end <= size - 1; end++) {          // window = frames (end-4 .. end)
            double lv = levelOf.get(end);
            if (lv <= bestLevel) { bestLevel = lv; best = end - LEVEL_FRAMES / 2 + 1; }
        }
        return Math.max(1, Math.min(size, best));
    }

    /**
     * Turns the held frames [0, end) into a segment, or drops them (with a note) when they are not worth sending. {@code flush}:
     * the speaker stopped, so a short last word right after speech is allowed and silence is added after the audio.
     */
    private Segment build(int end, boolean flush) {
        int vms = voicedMs(0, end);
        int first = firstVoiced();
        if (vms == 0 || first < 0 || first >= end) {
            notes.add("no sustained speech in " + end * FRAME_MS + "ms");
            return null;
        }
        long firstGlobal = heldStartFrame + first;
        boolean isolated = prevVoicedEndFrame < 0 || (firstGlobal - prevVoicedEndFrame) * FRAME_MS > ISOLATED_MS;

        int required = flush ? flushMinSpeechMs : minSpeechMs;
        if (singleSpeaker && !conversational && isolated) required = Math.max(required, ISOLATED_MIN_MS);
        if (vms < required) {
            notes.add("dropped " + vms + "ms of voiced sound (needs " + required + "ms" + (isolated ? ", standing alone" : "") + ")");
            return null;
        }

        double sum = 0;
        int n = 0;
        for (int i = 0; i < end; i++) if (voiced.get(i)) { sum += levelOf.get(i); n++; }
        double level = n == 0 ? 0 : sum / n;
        if (singleSpeaker && !conversational && sessionLevel > 0 && sessionVoicedMs >= SESSION_REF_MIN_MS
                && level < (isolated ? BACKGROUND_OF_SESSION_ISOLATED : BACKGROUND_OF_SESSION) * sessionLevel) {
            notes.add("dropped " + vms + "ms as background (level " + Math.round(level) + " against the speaker's " + Math.round(sessionLevel) + ")");
            return null;
        }
        sessionLevel = sessionLevel == 0 ? level : sessionLevel + Math.min(0.5, vms / 8000.0) * (level - sessionLevel);
        sessionVoicedMs += vms;
        prevVoicedEndFrame = heldStartFrame + lastVoicedBefore(end);

        ByteArrayOutputStream b = new ByteArrayOutputStream();
        for (int i = 0; i < end; i++) b.write(frames.get(i), 0, FRAME_BYTES);
        if (flush) b.write(new byte[PAD_MS / FRAME_MS * FRAME_BYTES], 0, PAD_MS / FRAME_MS * FRAME_BYTES);
        long startMs = heldStartFrame * FRAME_MS;
        Segment s = new Segment(b.toByteArray(), vms, startMs, startMs + (long) end * FRAME_MS, carryOverlap);
        carryOverlap = false;
        return s;
    }

    private int lastVoicedBefore(int end) {
        for (int i = Math.min(end, voiced.size()) - 1; i >= 0; i--) if (voiced.get(i)) return i;
        return 0;
    }

    private void removeFront(int count) {
        if (count <= 0) return;
        frames.subList(0, count).clear();
        energy.subList(0, count).clear();
        levelOf.subList(0, count).clear();
        quiet.subList(0, count).clear();
        voiced.subList(0, count).clear();
        heldStartFrame += count;
    }

    private void dropFront(int count) {
        removeFront(Math.min(count, frames.size()));
    }

    private void trimLeadingQuiet(int keep) {
        int lead = 0;
        while (lead < quiet.size() && quiet.get(lead) && !voiced.get(lead)) lead++;
        if (lead - keep > 0) removeFront(lead - keep);
    }

    private void clearHeld() {
        frames.clear();
        energy.clear();
        levelOf.clear();
        quiet.clear();
        voiced.clear();
        heldStartFrame = receivedFrames;
        runLen = 0;
    }
}
