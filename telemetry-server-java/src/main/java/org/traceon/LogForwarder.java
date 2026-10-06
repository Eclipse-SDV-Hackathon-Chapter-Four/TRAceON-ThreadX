/*
 * Optional log forwarding sink (SEPARATE from SSE): when started, each received
 * LogEntry is POSTed to TRACEON_LOG_FORWARD_URL. Fire-and-forget on a background
 * worker thread + bounded queue so it never blocks MQTT ingestion. OFF by default;
 * started/stopped at runtime via control endpoints.
 */
package org.traceon;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public final class LogForwarder {

    public static final LogForwarder INSTANCE = new LogForwarder(
            Config.env("TRACEON_LOG_FORWARD_URL", ""));

    private volatile String url;
    private final AtomicBoolean enabled = new AtomicBoolean(false);
    private final BlockingQueue<Map<String, Object>> queue = new ArrayBlockingQueue<>(1000);
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();
    private volatile Thread worker;

    private final AtomicLong forwarded = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();
    private final AtomicLong dropped = new AtomicLong();

    private LogForwarder(String url) { this.url = url; }

    public synchronized void setUrl(String u) { this.url = u; }

    public synchronized void start() {
        if (enabled.get()) return;
        if (url == null || url.isBlank())
            throw new IllegalStateException("No forward URL configured (set TRACEON_LOG_FORWARD_URL)");
        enabled.set(true);
        worker = new Thread(this::run, "log-forwarder");
        worker.setDaemon(true);
        worker.start();
    }

    public synchronized void stop() {
        enabled.set(false);
        if (worker != null) worker.interrupt();
    }

    /** Called from the MQTT thread; non-blocking, drops if the queue is full. */
    public void submit(Map<String, Object> entry) {
        if (!enabled.get()) return;
        if (!queue.offer(entry)) dropped.incrementAndGet();
    }

    public Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", enabled.get());
        m.put("url", (url == null || url.isBlank()) ? null : url);
        m.put("forwarded", forwarded.get());
        m.put("failed", failed.get());
        m.put("dropped", dropped.get());
        m.put("queued", queue.size());
        return m;
    }

    private void run() {
        while (enabled.get()) {
            Map<String, Object> entry;
            try {
                entry = queue.poll(500, java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                break;
            }
            if (entry != null) post(entry);
        }
    }

    private void post(Map<String, Object> entry) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(Json.toJson(entry)))
                    .build();
            http.send(req, HttpResponse.BodyHandlers.discarding());
            forwarded.incrementAndGet();
        } catch (Exception e) {
            failed.incrementAndGet();
        }
    }
}
