/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 the TRAceON team
 * Portions of this file were generated with AI assistance.
 */
package org.traceon;

import static org.junit.jupiter.api.Assertions.*;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

class EnvelopeJsonTest {

    @Test
    void envelope_successShape() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("msg", "hi");
        Map<String, Object> env = EventEnvelope.wrap(payload);
        assertTrue(env.containsKey("timestamp"));
        assertEquals(payload, env.get("payload"));
        assertNull(env.get("error"));
        // ISO-8601 UTC 'Z'
        assertTrue(env.get("timestamp").toString()
                .matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z"));
    }

    @Test
    void envelope_withError() {
        Map<String, Object> env = EventEnvelope.wrap(null, "boom");
        assertEquals("boom", env.get("error"));
        assertNull(env.get("payload"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void json_roundTripMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("severity", "DLT_WARN");
        m.put("msg", "x");
        String json = Json.toJson(m);
        assertTrue(json.contains("\"severity\""));
        Map<String, Object> back = Json.fromJson(json, Map.class);
        assertEquals("DLT_WARN", back.get("severity"));
        assertEquals("x", back.get("msg"));
    }
}
