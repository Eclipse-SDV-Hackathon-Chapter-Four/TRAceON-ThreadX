/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 Contributors to the Eclipse Foundation
 * SPDX-License-Identifier: MIT
 * Portions of this file were generated with AI assistance.
 */
package org.traceon;

import static org.junit.jupiter.api.Assertions.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** Unit tests for the pure logic in LogStore (same package -> can call statics). */
class LogStoreTest {

    // ---- severity mapping ----

    @Test
    void mapSeverity_dltPassthrough() {
        assertEquals("DLT_WARN", LogStore.mapSeverity("DLT_WARN"));
        assertEquals("DLT_INFO", LogStore.mapSeverity("DLT_INFO"));
    }

    @Test
    void mapSeverity_legacyBareMapped() {
        assertEquals("DLT_WARN", LogStore.mapSeverity("WARN"));
        assertEquals("DLT_WARN", LogStore.mapSeverity("warning"));
        assertEquals("DLT_ERROR", LogStore.mapSeverity("error"));
        assertEquals("DLT_VERBOSE", LogStore.mapSeverity("VERBOSE"));
    }

    @Test
    void mapSeverity_emptyDefaultsToInfo() {
        assertEquals("DLT_INFO", LogStore.mapSeverity(""));
        assertEquals("DLT_INFO", LogStore.mapSeverity("   "));
    }

    @Test
    void mapSeverity_unknownPassthrough() {
        assertEquals("CUSTOM", LogStore.mapSeverity("CUSTOM"));
    }

    // ---- contextIdOf ----

    @Test
    void contextIdOf_objectContext() {
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("context_id", "MQTT");
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("context", ctx);
        assertEquals("MQTT", LogStore.contextIdOf(entry));
    }

    @Test
    void contextIdOf_stringContext() {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("context", "Main");
        assertEquals("Main", LogStore.contextIdOf(entry));
    }

    @Test
    void contextIdOf_missing() {
        assertEquals("", LogStore.contextIdOf(new LinkedHashMap<>()));
    }

    // ---- normalize ----

    @Test
    void normalize_wrapsStringContextAndMapsSeverity() {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("context", "Main");
        entry.put("severity", "INFO");
        LogStore.normalize(entry);
        assertTrue(entry.get("context") instanceof Map);
        @SuppressWarnings("unchecked")
        Map<String, Object> ctx = (Map<String, Object>) entry.get("context");
        assertEquals("AUTOSAR_DLT", ctx.get("type"));
        assertEquals("Main", ctx.get("context_id"));
        assertEquals("DLT_INFO", entry.get("severity"));
        assertEquals("", entry.get("timestamp"));   // filled default
        assertEquals("", entry.get("msg"));
    }

    // ---- fallback ----

    @Test
    void fallback_keepsRawPayloadAsMsg() {
        Map<String, Object> e = LogStore.fallback("not json");
        assertEquals("not json", e.get("msg"));
        assertEquals("DLT_INFO", e.get("severity"));
        @SuppressWarnings("unchecked")
        Map<String, Object> ctx = (Map<String, Object>) e.get("context");
        assertEquals("AUTOSAR_DLT", ctx.get("type"));
    }

    // ---- ring buffer via public add() ----

    @Test
    void add_parsesIsoEntryAndNormalizesSeverity() {
        LogStore s = newStore(10);
        Map<String, Object> entry = s.add(
            "{\"timestamp\":\"t\",\"context\":{\"type\":\"AUTOSAR_DLT\",\"context_id\":\"X\"}," +
            "\"severity\":\"WARN\",\"msg\":\"hi\"}");
        assertEquals("DLT_WARN", entry.get("severity"));   // legacy mapped
        assertEquals("hi", entry.get("msg"));
    }

    @Test
    void add_malformedUsesFallback() {
        LogStore s = newStore(10);
        Map<String, Object> entry = s.add("totally not json");
        assertEquals("totally not json", entry.get("msg"));
        assertEquals("DLT_INFO", entry.get("severity"));
    }

    @Test
    void ringBuffer_evictsOldestButCountIsCumulative() {
        LogStore s = newStore(2);
        for (int i = 0; i < 5; i++) {
            s.add("{\"msg\":\"m" + i + "\",\"severity\":\"INFO\"}");
        }
        List<Map<String, Object>> entries = s.entries();
        assertEquals(2, entries.size());
        assertEquals("m3", entries.get(0).get("msg"));
        assertEquals("m4", entries.get(1).get("msg"));
        assertEquals(5, s.count());        // total ingested, not buffer size
    }

    @Test
    void slots_haveReceivedAtAndEntry() {
        LogStore s = newStore(4);
        s.add("{\"msg\":\"x\",\"severity\":\"INFO\"}");
        LogStore.Slot slot = s.slots().get(0);
        assertTrue(slot.received_at_ms > 0);
        assertEquals("x", slot.entry.get("msg"));
    }

    /** The singleton ctor is private; build an instance via reflection for isolation. */
    private static LogStore newStore(int maxLen) {
        try {
            var ctor = LogStore.class.getDeclaredConstructor(int.class);
            ctor.setAccessible(true);
            return ctor.newInstance(maxLen);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}