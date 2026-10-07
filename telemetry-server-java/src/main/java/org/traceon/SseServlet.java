/*
 * Plain Jakarta async servlet implementing the two SSE streams:
 *   GET /telemetry/stream  -> channel "telemetry"
 *   GET /logs/stream       -> channel "logs"
 *
 * Uses the Servlet async API directly (robust on embedded Jetty, unlike Jersey
 * SSE here). The connection is registered with SseRegistry, which gives each
 * subscriber its OWN bounded queue + writer thread (so a slow client can't
 * block the MQTT publisher) and sends periodic keep-alive comments itself.
 */
package org.traceon;

import java.io.IOException;
import java.io.PrintWriter;

import jakarta.servlet.AsyncContext;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

public class SseServlet extends HttpServlet {

    private final String channel;

    public SseServlet(String channel) {
        this.channel = channel;
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        resp.setStatus(200);
        resp.setContentType("text/event-stream");
        resp.setCharacterEncoding("UTF-8");
        resp.setHeader("Cache-Control", "no-cache");
        resp.setHeader("Connection", "keep-alive");

        final AsyncContext async = req.startAsync();
        async.setTimeout(0); // never time out; the writer thread manages keep-alive
        final PrintWriter writer = resp.getWriter();

        // Initial comment so clients/proxies see the stream open immediately.
        writer.write(": connected\n\n");
        writer.flush();

        // Register; SseRegistry starts a dedicated writer thread for this client.
        final SseRegistry.Subscriber sub = SseRegistry.INSTANCE.add(channel, writer);
        // When the subscriber's writer thread detects the client is gone, finish
        // the async context so the servlet container releases the connection.
        sub.setOnClose(() -> { try { async.complete(); } catch (Exception ignored) {} });

        async.addListener(new jakarta.servlet.AsyncListener() {
            public void onComplete(jakarta.servlet.AsyncEvent e) { SseRegistry.INSTANCE.remove(sub); }
            public void onTimeout(jakarta.servlet.AsyncEvent e) { SseRegistry.INSTANCE.remove(sub); }
            public void onError(jakarta.servlet.AsyncEvent e) { SseRegistry.INSTANCE.remove(sub); }
            public void onStartAsync(jakarta.servlet.AsyncEvent e) {}
        });
    }
}
