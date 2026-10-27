package com.aia.voiceinsights.api.voice;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Hands finished transcripts on in the order the speech was cut, not the order Azure happened to answer in.
 *
 * <p>Azure sometimes answers a later segment before an earlier one (one segment stalls and is re-sent, or is answered by
 * the fallback while the next one is already back). Without this the sentences land in the transcript out of order
 * ("Okay, Mr Rahim is 35." after "…and I said no problem."). Every segment reserves a number when it is cut and keeps it
 * through retries and a replaced connection; a finished transcript waits here until every earlier number is settled,
 * either with text or as skipped (blank, invented, or given up on).
 *
 * <p>One segment is never allowed to block the rest for long: after {@link #MAX_HOLD_MS} the later transcripts are
 * released, and the straggler is handed on whenever it does arrive.
 *
 * <p>The callbacks run one at a time, in order, on their own thread, so a callback may freely call back into the client
 * without any chance of a lock cycle.
 */
public class TranscriptSequencer {

    static final long MAX_HOLD_MS = 14_000;

    private final AtomicLong next = new AtomicLong(1);
    /** Reserved and not yet settled: sequence number -> when it was reserved. */
    private final ConcurrentSkipListMap<Long, Long> open = new ConcurrentSkipListMap<>();
    /** Settled and waiting for earlier numbers: sequence number -> what to do (null = skipped). */
    private final ConcurrentSkipListMap<Long, Runnable> done = new ConcurrentSkipListMap<>();
    private final Object lock = new Object();
    // One thread, so the callbacks stay in order; it is released when idle so a finished session leaves nothing behind.
    private final ExecutorService callbacks = newDeliveryExecutor();

    private static ExecutorService newDeliveryExecutor() {
        java.util.concurrent.ThreadPoolExecutor ex = new java.util.concurrent.ThreadPoolExecutor(1, 1, 20, java.util.concurrent.TimeUnit.SECONDS,
                new java.util.concurrent.LinkedBlockingQueue<>(), r -> {
            Thread t = new Thread(r, "voice-stt-delivery");
            t.setDaemon(true);
            return t;
        });
        ex.allowCoreThreadTimeOut(true);
        return ex;
    }

    /** The text last handed on; the next overlapping segment is trimmed against it. */
    private volatile String lastDelivered;

    /** A number for a segment that is being cut now. */
    public long reserve() {
        long seq = next.getAndIncrement();
        open.put(seq, System.currentTimeMillis());
        return seq;
    }

    /** The segment has text: hand it on once every earlier segment is settled. */
    public void complete(long seq, Runnable deliver) {
        settle(seq, deliver);
    }

    /** The segment produced nothing to keep (blank, invented, or given up on). */
    public void skip(long seq) {
        settle(seq, null);
    }

    public String lastDelivered() { return lastDelivered; }

    public void setLastDelivered(String text) { lastDelivered = text; }

    /** Releases transcripts that have waited behind an unanswered segment for too long; call it now and then. */
    public void release() {
        settle(-1, null);
    }

    private void settle(long seq, Runnable deliver) {
        List<Runnable> ready = new ArrayList<>();
        synchronized (lock) {
            if (seq >= 0) {
                if (open.remove(seq) != null) {
                    done.put(seq, deliver == null ? NOOP : deliver);
                } else if (deliver != null) {
                    ready.add(deliver); // it was released as a straggler: hand it on late rather than lose it
                }
            }
            long now = System.currentTimeMillis();
            while (!done.isEmpty()) {
                Map.Entry<Long, Runnable> first = done.firstEntry();
                Map.Entry<Long, Long> head = open.firstEntry();
                if (head != null && head.getKey() < first.getKey()) {
                    if (now - head.getValue() <= MAX_HOLD_MS) break; // an earlier segment is still being transcribed: wait for it
                    System.out.println("[voice-stt] segment " + head.getKey() + " is taking too long; passing on the later ones first");
                    open.remove(head.getKey());
                }
                done.remove(first.getKey());
                if (first.getValue() != NOOP) ready.add(first.getValue());
            }
        }
        for (Runnable r : ready) callbacks.execute(() -> {
            try {
                r.run();
            } catch (RuntimeException e) {
                System.err.println("[voice-stt] a transcript callback failed: " + e);
            }
        });
    }

    private static final Runnable NOOP = () -> { };

    /** Stops the delivery thread; whatever is still waiting is dropped. */
    public void close() {
        callbacks.shutdown();
    }
}
