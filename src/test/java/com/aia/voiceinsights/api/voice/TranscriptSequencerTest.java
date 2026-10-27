package com.aia.voiceinsights.api.voice;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TranscriptSequencerTest {

    private static void await(CountDownLatch l) throws InterruptedException {
        l.await(3, TimeUnit.SECONDS);
    }

    @Test
    void laterSegmentWaitsForTheEarlierOne() throws Exception {
        TranscriptSequencer s = new TranscriptSequencer();
        List<String> out = new CopyOnWriteArrayList<>();
        CountDownLatch done = new CountDownLatch(3);
        long a = s.reserve(), b = s.reserve(), c = s.reserve();
        s.complete(b, () -> { out.add("B"); done.countDown(); });
        s.complete(c, () -> { out.add("C"); done.countDown(); });
        Thread.sleep(100);
        assertEquals(List.of(), out); // A has not come back yet
        s.complete(a, () -> { out.add("A"); done.countDown(); });
        await(done);
        assertEquals(List.of("A", "B", "C"), out);
        s.close();
    }

    @Test
    void aSkippedSegmentDoesNotBlockTheRest() throws Exception {
        TranscriptSequencer s = new TranscriptSequencer();
        List<String> out = new CopyOnWriteArrayList<>();
        CountDownLatch done = new CountDownLatch(2);
        long a = s.reserve(), b = s.reserve(), c = s.reserve();
        s.complete(c, () -> { out.add("C"); done.countDown(); });
        s.skip(b); // invented line, discarded
        s.complete(a, () -> { out.add("A"); done.countDown(); });
        await(done);
        assertEquals(List.of("A", "C"), out);
        s.close();
    }

    @Test
    void inOrderAnswersAreDeliveredImmediately() throws Exception {
        TranscriptSequencer s = new TranscriptSequencer();
        List<String> out = new CopyOnWriteArrayList<>();
        CountDownLatch done = new CountDownLatch(2);
        long a = s.reserve(), b = s.reserve();
        s.complete(a, () -> { out.add("A"); done.countDown(); });
        s.complete(b, () -> { out.add("B"); done.countDown(); });
        await(done);
        assertEquals(List.of("A", "B"), out);
        s.close();
    }
}
