/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 Contributors to the Eclipse Foundation
 * SPDX-License-Identifier: MIT
 * Portions of this file were generated with AI assistance.
 */
/*
 * Query helpers for the /history endpoints: ISO-8601 since/until parsing (to
 * epoch millis) and limit clamping. Time filtering is on the server-side
 * receive time, per the agreed design.
 */
package org.traceon;

import java.time.Instant;
import java.time.OffsetDateTime;

public final class Query {

    public static final int MAX_LIMIT = 100;

    private Query() {}

    public static int clampLimit(Integer limit) {
        if (limit == null) return MAX_LIMIT;
        if (limit < 1) return 1;
        return Math.min(limit, MAX_LIMIT);
    }

    /** Parse ISO-8601 to epoch millis. null -> null. Throws on malformed input. */
    public static Long parseIso8601Millis(String value) {
        if (value == null || value.isBlank()) return null;
        String text = value.trim();
        try {
            return Instant.parse(text).toEpochMilli();           // e.g. ...Z
        } catch (Exception e) {
            return OffsetDateTime.parse(text).toInstant().toEpochMilli(); // with offset
        }
    }

    public static boolean inRange(long tsMs, Long sinceMs, Long untilMs) {
        if (sinceMs != null && tsMs < sinceMs) return false;
        if (untilMs != null && tsMs > untilMs) return false;
        return true;
    }
}