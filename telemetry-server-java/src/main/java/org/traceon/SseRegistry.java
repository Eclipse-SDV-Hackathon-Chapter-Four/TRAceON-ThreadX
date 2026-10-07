/*
 * Plain-servlet SSE fan-out (no JAX-RS SSE).
 *
 * Why not JAX-RS SSE: Jersey's SseEventSink requires async servlet support that
 * Jersey's programmatically-registered ServletContainer does not reliably detect
 * on embedded Jetty. So we implement SSE directly with Jetty async servlets.
 *
 * Slow-client isolation (code-review fix): publish() must NEVER block the caller
 * (the MQTT ingestion thread). Each subscriber owns a BOUNDED queue and a
 * dedicated writer thread that does the blocking socket write. publish() only
 * offers to each queue (drop-OLDEST when full), so one slow/stuck client can
 * neither block the publisher nor stall other subscribers. A periodic
 * keep-alive comment is sent so idle connections / proxies don't time out.
 */
package org.traceon;

import java.io.PrintWriter;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public final class SseRegistry {

    public static final SseRegistry INSTANCE = new SseRegistry();

    /** Per-connection subscriber: bounded queue + dedicated writer thread. */
    public static final class Subscriber {
        private final String channel;
        private final PrintWriter writer;
        private final BlockingQueue<String> queue = new ArrayBlockingQueue<>(256);
        private final Thread thread;
        private volatile boolean closed = false;
        private Runnable onClose = () -> {};

        private Subscriber(String channel, PrintWriter writer) {
            this.channel = channel;
            this.writer = writer;
            this.thread = new Thread(this::runWriter, "sse-" + channel);
            this.thread.setDaemon(true);
        }

        private void runWriter() {
            try {
                while (!closed) {
                    // Block up to the keep-alive interval; on timeout, send a
                    // comment so idle connections stay open.
                    String msg = queue.poll(15, TimeUnit.SECONDS);
                    synchronized (writer) {
                        if (msg != null) {
                            writer.write("data: " + msg + "\n\n");
                        } else {
                            writer.write(": keep-alive\n\n");
                        }
                        writer.flush();
                    }
                    if (writer.checkError()) break;  // client gone
                }
            } catch (InterruptedException ignored) {
                // shutting down
            } finally {
                close();
            }
        }

        /** Non-blocking offer; drop OLDEST if the per-client queue is full. */
        private void offer(String msg) {
            if (!queue.offer(msg)) {
                queue.poll();        // drop oldest
                queue.offer(msg);
            }
        }

        void close() {
            if (closed) return;
            closed = true;
            thread.interrupt();
            INSTANCE.channel(channel).remove(this);
            try { onClose.run(); } catch (Exception ignored) {}
        }

        void setOnClose(Runnable r) { this.onClose = r; }
    }

    private final Set<Subscriber> telemetry = ConcurrentHashMap.newKeySet();
    private final Set<Subscriber> logs = ConcurrentHashMap.newKeySet();

    private SseRegistry() {}

    private Set<Subscriber> channel(String c) { return "logs".equals(c) ? logs : telemetry; }

    /** Register a new SSE connection; starts its writer thread. */
    public Subscriber add(String channel, PrintWriter w) {
        Subscriber s = new Subscriber(channel, w);
        channel(channel).add(s);
        s.thread.start();
        return s;
    }

    public void remove(Subscriber s) {
        if (s != null) s.close();
    }

    /** Push a pre-serialized JSON string to all subscribers of a channel.
     *  Non-blocking: never blocks the caller (the MQTT thread). */
    public void publish(String channel, String json) {
        for (Subscriber s : channel(channel)) {
            s.offer(json);
        }
    }
}
