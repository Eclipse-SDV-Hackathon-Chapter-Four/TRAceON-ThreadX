/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 the TRAceON team
 * Portions of this file were generated with AI assistance.
 */
/*
 * ISO 17978-3 EventEnvelope (Table 5): wraps events on SSE streams.
 *   { "timestamp": "<server emit time>", "payload": <AnyValue>, "error": <string|null> }
 */
package org.traceon;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

public final class EventEnvelope {

    private static final DateTimeFormatter FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC);

    private EventEnvelope() {}

    public static Map<String, Object> wrap(Object payload) {
        return wrap(payload, null);
    }

    public static Map<String, Object> wrap(Object payload, String error) {
        Map<String, Object> env = new LinkedHashMap<>();
        env.put("timestamp", FMT.format(Instant.now()));
        env.put("payload", payload);
        env.put("error", error);
        return env;
    }
}
