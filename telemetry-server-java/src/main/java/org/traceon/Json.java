/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 the TRAceON team
 * Portions of this file were generated with AI assistance.
 */
/*
 * Tiny JSON serializer using Jakarta JSON Binding (Eclipse Yasson), reused for
 * SSE payloads so they match the JSON produced by the JAX-RS routes.
 */
package org.traceon;

import jakarta.json.bind.Jsonb;
import jakarta.json.bind.JsonbBuilder;

public final class Json {
    private static final Jsonb JSONB = JsonbBuilder.create();

    private Json() {}

    public static String toJson(Object o) {
        return JSONB.toJson(o);
    }

    public static <T> T fromJson(String json, Class<T> type) {
        return JSONB.fromJson(json, type);
    }
}
