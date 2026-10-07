/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 Contributors to the Eclipse Foundation
 * SPDX-License-Identifier: MIT
 * Portions of this file were generated with AI assistance.
 */
package org.traceon;

import static org.junit.jupiter.api.Assertions.*;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

class QueryTest {

    @Test
    void clampLimit_defaultsToMaxWhenNull() {
        assertEquals(Query.MAX_LIMIT, Query.clampLimit(null));
    }

    @Test
    void clampLimit_bounds() {
        assertEquals(1, Query.clampLimit(0));
        assertEquals(1, Query.clampLimit(-5));
        assertEquals(50, Query.clampLimit(50));
        assertEquals(Query.MAX_LIMIT, Query.clampLimit(999));
    }

    @Test
    void parseIso8601_nullAndBlankReturnNull() {
        assertNull(Query.parseIso8601Millis(null));
        assertNull(Query.parseIso8601Millis("   "));
    }

    @Test
    void parseIso8601_acceptsZuluAndOffset() {
        long z = Query.parseIso8601Millis("2026-10-06T17:00:00Z");
        long off = Query.parseIso8601Millis("2026-10-06T19:00:00+02:00");
        assertEquals(z, off);   // same instant
    }

    @Test
    void parseIso8601_malformedThrows() {
        assertThrows(Exception.class, () -> Query.parseIso8601Millis("not-a-date"));
    }

    @Test
    void inRange_bounds() {
        assertTrue(Query.inRange(100L, null, null));
        assertTrue(Query.inRange(100L, 50L, 150L));
        assertTrue(Query.inRange(50L, 50L, 150L));   // lower inclusive
        assertTrue(Query.inRange(150L, 50L, 150L));  // upper inclusive
        assertFalse(Query.inRange(49L, 50L, 150L));
        assertFalse(Query.inRange(151L, 50L, 150L));
    }
}