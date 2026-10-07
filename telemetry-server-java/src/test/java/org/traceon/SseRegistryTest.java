/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 Contributors to the Eclipse Foundation
 * SPDX-License-Identifier: MIT
 * Portions of this file were generated with AI assistance.
 */
package org.traceon;

import static org.junit.jupiter.api.Assertions.*;

import java.io.PrintWriter;
import java.io.Writer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.Timeout.ThreadMode;

/**
 * Verifies the slow-client isolation contract of {@link SseRegistry} (the
 * code-review concurrency fix that was previously untested — see LIMITATIONS.md):
 *
 *   1. A stuck subscriber (its socket write blocks forever) does NOT stall other
 *      subscribers — a fast client keeps receiving.
 *   2. publish() never blocks the caller (the MQTT ingestion thread), even with a
 *      stuck subscriber and even under a flood that overruns the bounded queue.
 *
 * Tests run in the same package so they can use the package-private add/remove.
 */
class SseRegistryTest {

    /** A Writer whose write() blocks until released — simulates a stuck client. */
    private static final class BlockingWriter extends Writer {
        private final CountDownLatch release;
        BlockingWriter(CountDownLatch release) { this.release = release; }
        @Override public void write(char[] cbuf, int off, int len) {
            try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        @Override public void flush() {}
        @Override public void close() {}
    }

    /** A Writer that records every full data frame it is asked to write. */
    private static final class RecordingWriter extends Writer {
        final List<String> frames = new ArrayList<>();
        final AtomicInteger dataFrames = new AtomicInteger();
        private final StringBuilder cur = new StringBuilder();
        @Override public synchronized void write(char[] cbuf, int off, int len) {
            cur.append(cbuf, off, len);
        }
        @Override public synchronized void flush() {
            String s = cur.toString();
            cur.setLength(0);
            if (s.isEmpty()) return;
            frames.add(s);
            if (s.startsWith("data: ")) dataFrames.incrementAndGet();
        }
        @Override public void close() {}
    }

    @Test
    @Timeout(value = 10, threadMode = ThreadMode.SEPARATE_THREAD) // hang-proof: abandons a wedged test instead of wedging CI
    void publishDoesNotBlockWhenOneSubscriberIsStuck() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        BlockingWriter stuckW = new BlockingWriter(release);
        RecordingWriter fastW = new RecordingWriter();

        SseRegistry.Subscriber stuck = SseRegistry.INSTANCE.add("telemetry", new PrintWriter(stuckW));
        SseRegistry.Subscriber fast  = SseRegistry.INSTANCE.add("telemetry", new PrintWriter(fastW));
        try {
            // Give the stuck client's writer thread a moment to grab its first
            // item and block inside write() on the blocking socket.
            Thread.sleep(100);

            // publish() must return promptly even though 'stuck' is wedged.
            long start = System.nanoTime();
            for (int i = 0; i < 10; i++) {
                SseRegistry.INSTANCE.publish("telemetry", "{\"n\":" + i + "}");
            }
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            assertTrue(elapsedMs < 500,
                "publish() blocked (" + elapsedMs + "ms) — a stuck client stalled the publisher");

            // The fast client must still be receiving while 'stuck' is wedged.
            boolean fastGotData = awaitTrue(() -> fastW.dataFrames.get() >= 10, 2000);
            assertTrue(fastGotData,
                "fast subscriber did not receive data while another was stuck (got "
                    + fastW.dataFrames.get() + " frames)");
        } finally {
            release.countDown();           // unstick the blocked writer thread
            SseRegistry.INSTANCE.remove(stuck);
            SseRegistry.INSTANCE.remove(fast);
        }
    }

    @Test
    @Timeout(value = 10, threadMode = ThreadMode.SEPARATE_THREAD) // hang-proof: abandons a wedged test instead of wedging CI
    void publishStaysNonBlockingUnderFloodToStuckSubscriber() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        BlockingWriter stuckW = new BlockingWriter(release);
        SseRegistry.Subscriber stuck = SseRegistry.INSTANCE.add("logs", new PrintWriter(stuckW));
        try {
            Thread.sleep(100); // let the writer thread block inside write()

            // Flood well past the bounded per-client queue (256). drop-oldest must
            // keep publish() non-blocking regardless of the stuck consumer.
            long start = System.nanoTime();
            for (int i = 0; i < 5_000; i++) {
                SseRegistry.INSTANCE.publish("logs", "{\"n\":" + i + "}");
            }
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            assertTrue(elapsedMs < 1000,
                "publish() blocked under flood (" + elapsedMs + "ms) — bounded queue did not drop");
        } finally {
            release.countDown();
            SseRegistry.INSTANCE.remove(stuck);
        }
    }

    /** Poll a condition until true or the timeout elapses. */
    private static boolean awaitTrue(java.util.function.BooleanSupplier cond, long timeoutMs)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (cond.getAsBoolean()) return true;
            TimeUnit.MILLISECONDS.sleep(10);
        }
        return cond.getAsBoolean();
    }
}
