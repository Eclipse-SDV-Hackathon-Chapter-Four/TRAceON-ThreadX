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

/* Keep comfortably within NXD_MQTT_MAX_MESSAGE_LENGTH (170). */
#define LOG_MSG_MAX 96
#define LOG_CTX_MAX 32
#define LOG_JSON_MAX 170

/* Convert Unix epoch seconds (UTC) to "YYYY-MM-DDThh:mm:ssZ".
 * Civil-date algorithm (Howard Hinnant), avoids libc localtime/timezone. */
static void epoch_to_iso8601(unsigned long epoch, char* out, int out_size)
{
    unsigned long days = epoch / 86400UL;
    unsigned long rem  = epoch % 86400UL;
    int hour = (int)(rem / 3600UL);
    int min  = (int)((rem % 3600UL) / 60UL);
    int sec  = (int)(rem % 60UL);

    /* days since 1970-01-01 -> civil y/m/d */
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
        if (c == '"' || c == '\\')
        {
            if (j >= dst_size - 3) break;
            dst[j++] = '\\';
            dst[j++] = c;
        }
        else if (c == '\n') { if (j >= dst_size - 3) break; dst[j++] = '\\'; dst[j++] = 'n'; }
        else if (c == '\r') { if (j >= dst_size - 3) break; dst[j++] = '\\'; dst[j++] = 'r'; }
        else if (c == '\t') { if (j >= dst_size - 3) break; dst[j++] = '\\'; dst[j++] = 't'; }
        else if ((unsigned char)c < 0x20) { continue; /* drop other control chars */ }
        else { dst[j++] = c; }
    }
    dst[j] = '\0';
}

void traceon_log(const char* context, const char* severity, const char* msg)
{
    char ts[32];
    char ctx_esc[LOG_CTX_MAX];
    char msg_esc[LOG_MSG_MAX];
    char json[LOG_JSON_MAX];

    unsigned long epoch = (unsigned long)sntp_time_get();
    epoch_to_iso8601(epoch, ts, sizeof(ts));

    json_escape(context, ctx_esc, sizeof(ctx_esc));
    json_escape(msg, msg_esc, sizeof(msg_esc));
    if (severity == NULL) severity = TRACEON_SEV_INFO;

    int n = npf_snprintf(json, sizeof(json),
        "{\"timestamp\":\"%s\",\"context\":\"%s\",\"severity\":\"%s\",\"msg\":\"%s\"}",
        ts, ctx_esc, severity, msg_esc);

    /* Mirror to the serial console. */
    printf("[LOG] %s/%s: %s\r\n", severity, ctx_esc, msg_esc);

    if (n > 0)
    {
        UINT len = (n < (int)sizeof(json)) ? (UINT)n : (UINT)(sizeof(json) - 1);
        mqtt_publish_log(json, len);
    }
}

void traceon_log_debug(const char* context, const char* msg) { traceon_log(context, TRACEON_SEV_DEBUG, msg); }
void traceon_log_info(const char* context, const char* msg)  { traceon_log(context, TRACEON_SEV_INFO, msg); }
void traceon_log_warn(const char* context, const char* msg)  { traceon_log(context, TRACEON_SEV_WARN, msg); }
void traceon_log_error(const char* context, const char* msg) { traceon_log(context, TRACEON_SEV_ERROR, msg); }
