/*
 * Plain Jakarta async servlet implementing the two SSE streams:
 *   GET /telemetry/stream  -> channel "telemetry"
 *   GET /logs/stream       -> channel "logs"
 *
 * Uses the Servlet async API directly (robust on embedded Jetty, unlike Jersey
 * SSE here). The writer is registered with SseRegistry; the MQTT thread pushes
 * events. A keep-alive comment is sent periodically by the publisher's traffic;
 * the async context has no timeout (0 = never) so streams stay open.
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
        async.setTimeout(0); // never time out
        final PrintWriter writer = resp.getWriter();

        // Initial comment so clients/proxies see the stream open immediately.
        writer.write(": connected\n\n");
        writer.flush();

        SseRegistry.INSTANCE.add(channel, writer);

        async.addListener(new jakarta.servlet.AsyncListener() {
            public void onComplete(jakarta.servlet.AsyncEvent e) { cleanup(); }
            public void onTimeout(jakarta.servlet.AsyncEvent e) { cleanup(); }
            public void onError(jakarta.servlet.AsyncEvent e) { cleanup(); }
            public void onStartAsync(jakarta.servlet.AsyncEvent e) {}
            private void cleanup() {
                SseRegistry.INSTANCE.remove(channel, writer);
                try { async.complete(); } catch (Exception ignored) {}
            }
        });
    }
}
