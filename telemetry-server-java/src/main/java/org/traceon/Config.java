/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 the TRAceON team
 * Portions of this file were generated with AI assistance.
 */
package org.traceon;

public final class Config {
    private Config() {}

    public static String env(String key, String def) {
        String v = System.getenv(key);
        return (v == null || v.isEmpty()) ? def : v;
    }

    public static final String COMPONENT = env("TRACEON_COMPONENT", "TRAceON");
    public static final int HTTP_PORT = Integer.parseInt(env("TRACEON_HTTP_PORT", "8082"));
    public static final int LOG_BUFFER_SIZE =
            Integer.parseInt(env("TRACEON_LOG_BUFFER_SIZE", "100"));
    public static final int TELEMETRY_HISTORY_SIZE =
            Integer.parseInt(env("TRACEON_TELEMETRY_HISTORY_SIZE", "100"));
}
