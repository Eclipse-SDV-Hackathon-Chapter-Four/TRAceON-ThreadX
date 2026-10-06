/*
 * Logs: thread-safe ring buffer of ISO 17978-3 LogEntry (Table 316) objects.
 *
 * Board publishes JSON on TRAceON/logs:
 *   { "timestamp": "<ISO-8601>",
 *     "context":  { "type": "AUTOSAR_DLT", "application_id": "TRAC",
 *                   "context_id": "SensorTask", "session": "", "session_id": "",
 *                   "message_id": "" },
 *     "severity": "DLT_WARN",
 *     "msg":      "..." }
 *
 * Entries are stored ISO-pure (timestamp/context/severity/msg). A private
 * server-side receive time is kept in the slot for history time-filtering only.
 * Legacy bare severities (WARN/ERROR/...) are mapped to DLT_* defensively.
 */
package org.traceon;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

public final class LogStore {

    public static final LogStore INSTANCE = new LogStore(Config.LOG_BUFFER_SIZE);

    /** ISO LogEntry — kept as a parsed JSON map so JSON-B re-serializes it as-is. */
    public static final class Slot {
        public long received_at_ms;      // private: for history filtering only
        public Map<String, Object> entry; // the ISO LogEntry (what we expose)
    }

    private final int maxLen;
    private final Deque<Slot> buf = new ArrayDeque<>();
    private final AtomicLong count = new AtomicLong(0);
    private final Object lock = new Object();

    private LogStore(int maxLen) { this.maxLen = maxLen; }

    /** Parse a raw payload into an ISO LogEntry map, add to the buffer, return it. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> add(String payload) {
        Map<String, Object> entry;
        try {
            entry = Json.fromJson(payload, Map.class);
            if (entry == null) throw new IllegalArgumentException("null");
            normalize(entry);
        } catch (Exception e) {
            entry = fallback(payload);
        }
        Slot s = new Slot();
        s.received_at_ms = System.currentTimeMillis();
        s.entry = entry;
        synchronized (lock) {
            if (buf.size() >= maxLen) buf.pollFirst();
            buf.addLast(s);
        }
        count.incrementAndGet();
        return entry;
    }

    /** Internal slots (entry + received_at), oldest first — for history filtering. */
    public List<Slot> slots() {
        synchronized (lock) { return new ArrayList<>(buf); }
    }

    /** Plain list of ISO LogEntry objects (ISO-pure). */
    public List<Map<String, Object>> entries() {
        synchronized (lock) {
            List<Map<String, Object>> out = new ArrayList<>(buf.size());
            for (Slot s : buf) out.add(s.entry);
            return out;
        }
    }

    public long count() { return count.get(); }

    // ---- helpers ----
    static void normalize(Map<String, Object> entry) {
        entry.putIfAbsent("timestamp", "");
        entry.putIfAbsent("msg", "");
        Object sev = entry.get("severity");
        entry.put("severity", mapSeverity(sev == null ? "" : sev.toString()));
        // context left as-is (an object from the board); if a bare string slipped
        // through, wrap it minimally.
        Object ctx = entry.get("context");
        if (!(ctx instanceof Map)) {
            Map<String, Object> c = new java.util.LinkedHashMap<>();
            c.put("type", "AUTOSAR_DLT");
            c.put("context_id", ctx == null ? "" : ctx.toString());
            entry.put("context", c);
        }
    }

    static Map<String, Object> fallback(String payload) {
        Map<String, Object> e = new java.util.LinkedHashMap<>();
        e.put("timestamp", "");
        Map<String, Object> c = new java.util.LinkedHashMap<>();
        c.put("type", "AUTOSAR_DLT"); c.put("context_id", "");
        e.put("context", c);
        e.put("severity", "DLT_INFO");
        e.put("msg", payload == null ? "" : payload.strip());
        return e;
    }

    /** Map legacy/bare severity to DLT_*; pass through already-DLT values. */
    static String mapSeverity(String s) {
        String t = s.trim();
        switch (t.toUpperCase()) {
            case "DLT_FATAL": case "FATAL": return "DLT_FATAL";
            case "DLT_ERROR": case "ERROR": return "DLT_ERROR";
            case "DLT_WARN": case "WARN": case "WARNING": return "DLT_WARN";
            case "DLT_INFO": case "INFO": return "DLT_INFO";
            case "DLT_DEBUG": case "DEBUG": return "DLT_DEBUG";
            case "DLT_VERBOSE": case "VERBOSE": return "DLT_VERBOSE";
            default: return t.isEmpty() ? "DLT_INFO" : t;
        }
    }

    /** Extract AUTOSAR_DLT context_id from an entry (for history filtering). */
    @SuppressWarnings("unchecked")
    static String contextIdOf(Map<String, Object> entry) {
        Object ctx = entry.get("context");
        if (ctx instanceof Map) {
            Object id = ((Map<String, Object>) ctx).get("context_id");
            return id == null ? "" : id.toString();
        }
        return ctx == null ? "" : ctx.toString();
    }
}
