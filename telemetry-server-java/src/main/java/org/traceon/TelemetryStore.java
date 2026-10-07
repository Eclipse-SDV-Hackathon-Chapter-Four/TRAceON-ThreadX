/*
 * Shared, thread-safe latest-telemetry store.
 * Fields are stored with proper types (Double / double[]) so JSON-B (Yasson)
 * serializes vectors as real JSON arrays, not strings.
 */
package org.traceon;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public final class TelemetryStore {

    public static final TelemetryStore INSTANCE = new TelemetryStore();

    /** A single historical reading (public fields → JSON-B serializes directly). */
    public static final class Reading {
        public long received_at_ms;        // server receive time
        public Map<String, Object> fields; // telemetry fields at that time
    }

    private final int historySize = Config.TELEMETRY_HISTORY_SIZE;
    private final Object lock = new Object();
    private Map<String, Object> fields = new LinkedHashMap<>();
    private final Deque<Reading> history = new ArrayDeque<>();
    private final AtomicLong messageCount = new AtomicLong(0);
    private final AtomicLong receivedAtMs = new AtomicLong(0);
    private final AtomicBoolean mqttConnected = new AtomicBoolean(false);

    private TelemetryStore() {}

    public void update(Map<String, Object> newFields) {
        long now = System.currentTimeMillis();
        synchronized (lock) {
            this.fields = newFields;
            Reading r = new Reading();
            r.received_at_ms = now;
            r.fields = new LinkedHashMap<>(newFields);
            if (history.size() >= historySize) history.pollFirst();
            history.addLast(r);
        }
        messageCount.incrementAndGet();
        receivedAtMs.set(now);
    }

    /** Snapshot copy of the reading history (oldest first). */
    public List<Reading> history() {
        synchronized (lock) {
            return new ArrayList<>(history);
        }
    }

    /** Snapshot copy of the latest fields. */
    public Map<String, Object> fields() {
        synchronized (lock) {
            return new LinkedHashMap<>(fields);
        }
    }

    public Object field(String name) {
        synchronized (lock) {
            return fields.get(name);
        }
    }

    public java.util.List<String> fieldNames() {
        synchronized (lock) {
            return new java.util.ArrayList<>(fields.keySet());
        }
    }

    public boolean hasData() { return receivedAtMs.get() != 0; }
    public long messageCount() { return messageCount.get(); }
    public void setMqttConnected(boolean v) { mqttConnected.set(v); }
    public boolean mqttConnected() { return mqttConnected.get(); }

    public long lastMessageAgeMs() {
        long t = receivedAtMs.get();
        return t == 0 ? -1 : System.currentTimeMillis() - t;
    }

    /** Age of the last message in SECONDS (null if none yet) — matches the
     *  Python server's /health "last_message_age_seconds" field. */
    public Double lastMessageAgeSeconds() {
        long t = receivedAtMs.get();
        if (t == 0) return null;
        return Math.round((System.currentTimeMillis() - t) / 10.0) / 100.0; // seconds, 2 dp
    }
}
