/*
 * Plain-servlet SSE fan-out (no JAX-RS SSE).
 *
 * Why not JAX-RS SSE: Jersey's SseEventSink requires async servlet support that
 * Jersey's programmatically-registered ServletContainer does not reliably detect
 * on embedded Jetty (reports "Servlet 2.x container" even when async IS enabled
 * and the context is Servlet 6). So we implement SSE directly with Jetty async
 * servlets, which is robust and dependency-free.
 *
 * This registry holds the active async writers per channel; the MQTT thread
 * calls publish(...) to push a JSON line to every subscriber on that channel.
 */
package org.traceon;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class SseRegistry {

    public static final SseRegistry INSTANCE = new SseRegistry();

    private final Set<PrintWriter> telemetry = ConcurrentHashMap.newKeySet();
    private final Set<PrintWriter> logs = ConcurrentHashMap.newKeySet();

    private SseRegistry() {}

    public void add(String channel, PrintWriter w) { channel(channel).add(w); }
    public void remove(String channel, PrintWriter w) { channel(channel).remove(w); }

    private Set<PrintWriter> channel(String c) { return "logs".equals(c) ? logs : telemetry; }

    /** Push a pre-serialized JSON string to all subscribers of a channel.
     *  Safe to call from the MQTT thread. Dead writers are pruned. */
    public void publish(String channel, String json) {
        Set<PrintWriter> set = channel(channel);
        for (PrintWriter w : set) {
            try {
                synchronized (w) {
                    w.write("data: " + json + "\n\n");
                    w.flush();
                }
                if (w.checkError()) {
                    set.remove(w); // client gone
                }
            } catch (Exception e) {
                set.remove(w);
            }
        }
    }
}
