/*
 * Logs: a thread-safe bounded ring buffer of log entries, separate from telemetry.
 *
 * The board publishes JSON log messages on TRAceON/logs with four string fields:
 *   {"timestamp":"2026-10-06T15:37:24.607Z","context":"SensorTask",
 *    "severity":"WARN","msg":"Humidity sensor returned no data"}
 *
 * Parsing is lenient: a malformed / non-JSON payload still yields an entry
 * (severity "UNKNOWN", raw text as msg) so nothing is silently dropped.
 */
package org.traceon;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

public final class LogStore {

    public static final LogStore INSTANCE = new LogStore(Config.LOG_BUFFER_SIZE);

    /** The incoming JSON shape (deserialized by JSON-B). All strings. */
    public static final class LogMessage {
        public String timestamp;
        public String context;
        public String severity;
        public String msg;
    }

    /** A stored log entry (public fields → JSON-B serializes directly for the API). */
    public static final class LogEntry {
        public long seq;
        public long received_at_ms;   // server-side receipt time (diagnostics)
        public String timestamp;      // board's ISO-8601 date-time (string)
        public String context;
        public String severity;
        public String msg;
    }

    private final int maxLen;
    private final Deque<LogEntry> buf = new ArrayDeque<>();
    private final AtomicLong seq = new AtomicLong(0);
    private final Object lock = new Object();

    private LogStore(int maxLen) { this.maxLen = maxLen; }

    public LogEntry add(String payload) {
        LogEntry e = parse(payload);
        e.seq = seq.incrementAndGet();
        e.received_at_ms = System.currentTimeMillis();
        synchronized (lock) {
            if (buf.size() >= maxLen) buf.pollFirst();
            buf.addLast(e);
        }
        return e;
    }

    public List<LogEntry> snapshot(Integer limit) {
        synchronized (lock) {
            List<LogEntry> all = new ArrayList<>(buf);
            if (limit != null && limit >= 0 && limit < all.size()) {
                return new ArrayList<>(all.subList(all.size() - limit, all.size()));
            }
            return all;
        }
    }

    public long count() { return seq.get(); }

    static LogEntry parse(String payload) {
        LogEntry e = new LogEntry();
        try {
            LogMessage m = Json.fromJson(payload, LogMessage.class);
            if (m != null) {
                e.timestamp = nullToEmpty(m.timestamp);
                e.context = nullToEmpty(m.context);
                e.severity = nullToEmpty(m.severity);
                e.msg = nullToEmpty(m.msg);
                return e;
            }
        } catch (Exception ignored) {
            // fall through to lenient fallback
        }
        e.timestamp = "";
        e.context = "";
        e.severity = "UNKNOWN";
        e.msg = payload == null ? "" : payload.strip();
        return e;
    }

    private static String nullToEmpty(String s) { return s == null ? "" : s; }
}
