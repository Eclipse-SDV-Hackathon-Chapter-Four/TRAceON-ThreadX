/*
 *  Copyright (c) 2026 Contributors to the Eclipse Foundation
 *
 *  This program and the accompanying materials are made available
 *  under the terms of the MIT license which is available at
 *  https://opensource.org/license/mit.
 *
 *  SPDX-License-Identifier: MIT
 */

#include "logger.h"

#include <stdio.h>
#include <string.h>

#include "nanoprintf.h"
#include "sntp_client.h"
#include "mqtt_client.h"

/* AUTOSAR_DLT application id for this device (4 chars by DLT convention). */
#define TRACEON_DLT_APP_ID "TRAC"

#define LOG_CTX_MAX  24    /* context_id */
#define LOG_MSG_MAX  96    /* message */
#define LOG_JSON_MAX 320   /* full ISO LogEntry JSON (see mqtt_client.h max) */

/* Convert Unix epoch seconds (UTC) to "YYYY-MM-DDThh:mm:ssZ".
 * Civil-date algorithm (Howard Hinnant), avoids libc localtime/timezone. */
static void epoch_to_iso8601(unsigned long epoch, char* out, int out_size)
{
    unsigned long rem  = epoch % 86400UL;
    unsigned long days = epoch / 86400UL;
    int hour = (int)(rem / 3600UL);
    int min  = (int)((rem % 3600UL) / 60UL);
    int sec  = (int)(rem % 60UL);

    long z = (long)days + 719468;
    long era = (z >= 0 ? z : z - 146096) / 146097;
    unsigned long doe = (unsigned long)(z - era * 146097);
    unsigned long yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365;
    long y = (long)yoe + era * 400;
    unsigned long doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    unsigned long mp = (5 * doy + 2) / 153;
    unsigned long d = doy - (153 * mp + 2) / 5 + 1;
    unsigned long m = mp < 10 ? mp + 3 : mp - 9;
    if (m <= 2) y += 1;

    npf_snprintf(out, out_size, "%04ld-%02lu-%02luT%02d:%02d:%02dZ",
                 y, m, d, hour, min, sec);
}

/* Copy src into dst (max dst_size incl NUL), escaping JSON-special chars. */
static void json_escape(const char* src, char* dst, int dst_size)
{
    int j = 0;
    if (src == NULL) { dst[0] = '\0'; return; }
    for (int i = 0; src[i] != '\0' && j < dst_size - 2; i++)
    {
        char c = src[i];
        if (c == '"' || c == '\\') { if (j >= dst_size - 3) break; dst[j++] = '\\'; dst[j++] = c; }
        else if (c == '\n') { if (j >= dst_size - 3) break; dst[j++] = '\\'; dst[j++] = 'n'; }
        else if (c == '\r') { if (j >= dst_size - 3) break; dst[j++] = '\\'; dst[j++] = 'r'; }
        else if (c == '\t') { if (j >= dst_size - 3) break; dst[j++] = '\\'; dst[j++] = 't'; }
        else if ((unsigned char)c < 0x20) { continue; }
        else { dst[j++] = c; }
    }
    dst[j] = '\0';
}

void traceon_log(const char* context_id, const char* severity, const char* msg)
{
    char ts[32];
    char ctx_esc[LOG_CTX_MAX];
    char msg_esc[LOG_MSG_MAX];
    char json[LOG_JSON_MAX];

    unsigned long epoch = (unsigned long)sntp_time_get();
    epoch_to_iso8601(epoch, ts, sizeof(ts));

    json_escape(context_id, ctx_esc, sizeof(ctx_esc));
    json_escape(msg, msg_esc, sizeof(msg_esc));
    if (severity == NULL) severity = TRACEON_SEV_INFO;

    /* ISO 17978-3 LogEntry (Table 316) with an AUTOSAR_DLT Context. */
    int n = npf_snprintf(json, sizeof(json),
        "{\"timestamp\":\"%s\","
        "\"context\":{\"type\":\"AUTOSAR_DLT\","
        "\"application_id\":\"%s\",\"context_id\":\"%s\","
        "\"session\":\"\",\"session_id\":\"\",\"message_id\":\"\"},"
        "\"severity\":\"%s\",\"msg\":\"%s\"}",
        ts, TRACEON_DLT_APP_ID, ctx_esc, severity, msg_esc);

    printf("[LOG] %s/%s: %s\r\n", severity, ctx_esc, msg_esc);

    if (n > 0)
    {
        UINT len = (n < (int)sizeof(json)) ? (UINT)n : (UINT)(sizeof(json) - 1);
        mqtt_publish_log(json, len);
    }
}

void traceon_log_fatal(const char* c, const char* m)   { traceon_log(c, TRACEON_SEV_FATAL, m); }
void traceon_log_error(const char* c, const char* m)   { traceon_log(c, TRACEON_SEV_ERROR, m); }
void traceon_log_warn(const char* c, const char* m)    { traceon_log(c, TRACEON_SEV_WARN, m); }
void traceon_log_info(const char* c, const char* m)    { traceon_log(c, TRACEON_SEV_INFO, m); }
void traceon_log_debug(const char* c, const char* m)   { traceon_log(c, TRACEON_SEV_DEBUG, m); }
void traceon_log_verbose(const char* c, const char* m) { traceon_log(c, TRACEON_SEV_VERBOSE, m); }
