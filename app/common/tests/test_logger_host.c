/*
 * Host-side unit tests for the firmware logger's PURE logic.
 *
 * The firmware is bare-metal (ThreadX/NetXDuo, cross-compiled for ARM), so most
 * of it can't run on the host. But logger.c's core helpers are hardware-free:
 *   - epoch_to_iso8601()  (static) — civil-date -> "YYYY-MM-DDThh:mm:ssZ"
 *   - json_escape()       (static) — JSON string escaping
 *   - traceon_log()                — assembles the ISO 17978-3 LogEntry JSON
 *
 * We compile this on the host by #including logger.c directly (to reach the
 * static helpers) and STUBBING its three hardware dependencies:
 *   - UINT/ULONG typedefs (normally from ThreadX nx_api.h)
 *   - sntp_time_get()      (fake clock)
 *   - mqtt_enqueue_log()   (captures the JSON instead of publishing)
 *
 * Build + run:  see app/common/tests/run.sh
 *
 * NOTE: this is BEST-EFFORT host coverage of pure logic — it does not exercise
 * the real RTOS, networking, SNTP or MQTT paths. Those require on-target or HIL
 * testing (e.g. via Eclipse openDuT — see OPENDUT-INTEGRATION.md).
 */
#include <assert.h>
#include <stdio.h>
#include <string.h>

/* --- Stubs for the firmware's hardware dependencies ---------------------- */

/* ThreadX integer types (normally from tx_api.h / nx_api.h). */
typedef unsigned int  UINT;
typedef unsigned long ULONG;

/* Fake SNTP clock: tests set this; epoch_to_iso8601 consumes it. */
static ULONG g_fake_epoch = 0;
ULONG sntp_time_get(void) { return g_fake_epoch; }

/* Capture the last enqueued JSON instead of sending it over MQTT.
   logger.c now calls mqtt_enqueue_log (C1: cross-thread-safe hand-off to the
   MQTT thread) rather than publishing directly. */
static char g_last_json[512];
static UINT g_last_len;
static int  g_publish_calls;
UINT mqtt_enqueue_log(const char* json, UINT length) {
    g_publish_calls++;
    g_last_len = length;
    size_t n = length < sizeof(g_last_json) - 1 ? length : sizeof(g_last_json) - 1;
    memcpy(g_last_json, json, n);
    g_last_json[n] = '\0';
    return 0;
}

/* nanoprintf: pull in the implementation for this translation unit. */
#define NANOPRINTF_IMPLEMENTATION
#define NANOPRINTF_USE_FIELD_WIDTH_FORMAT_SPECIFIERS 1
#define NANOPRINTF_USE_PRECISION_FORMAT_SPECIFIERS 1
#define NANOPRINTF_USE_LARGE_FORMAT_SPECIFIERS 1
#define NANOPRINTF_USE_FLOAT_FORMAT_SPECIFIERS 0
#define NANOPRINTF_USE_BINARY_FORMAT_SPECIFIERS 0
#define NANOPRINTF_USE_WRITEBACK_FORMAT_SPECIFIERS 0
#include "nanoprintf.h"

/* Pre-empt the real firmware headers that logger.c includes with "" (which
   resolve relative to logger.c's own dir, so -I can't shadow them). We define
   their include guards here and provide the declarations logger.c needs; the
   real headers then expand to nothing. This avoids pulling in ThreadX/NetXDuo
   (tx_api.h / nxd_mqtt_client.h) which don't exist on the host.
   Guard names confirmed from the real headers:
     sntp_client.h -> _SNTP_CLIENT_H
     nxd_mqtt_client.h (included by mqtt_client.h, outside its guard)
     mqtt_client.h -> MQTT_CLIENT_H */
#define _SNTP_CLIENT_H
#define MQTT_CLIENT_H
#define NXD_MQTT_CLIENT_H_   /* best-effort guard for nxd_mqtt_client.h */
#define NXD_MQTT_CLIENT_H
/* Declarations logger.c relies on (now that the real headers are shadowed): */
ULONG sntp_time_get(void);
UINT mqtt_enqueue_log(const char* json, UINT length);

/* We include logger.c AFTER the stubs so its static helpers become visible and
   its extern deps resolve to the stubs above. */
#include "logger.c"

/* --- Test helpers --------------------------------------------------------- */

static int g_failures = 0;
#define CHECK(cond, name) do { \
    if (cond) { printf("  [ok]   %s\n", name); } \
    else      { printf("  [FAIL] %s\n", name); g_failures++; } \
} while (0)

#define CHECK_STR(got, want, name) do { \
    if (strcmp((got), (want)) == 0) { printf("  [ok]   %s\n", name); } \
    else { printf("  [FAIL] %s: got \"%s\" want \"%s\"\n", name, (got), (want)); \
           g_failures++; } \
} while (0)

/* --- Tests ---------------------------------------------------------------- */

static void test_epoch_to_iso8601(void) {
    char out[32];
    /* 2026-10-07T07:56:40Z = 1791359800 (known Unix epoch) */
    epoch_to_iso8601(1791359800UL, out, sizeof(out));
    CHECK_STR(out, "2026-10-07T07:56:40Z", "epoch_to_iso8601 known value");

    /* Unix epoch 0 = 1970-01-01T00:00:00Z */
    epoch_to_iso8601(0UL, out, sizeof(out));
    CHECK_STR(out, "1970-01-01T00:00:00Z", "epoch_to_iso8601 zero");

    /* A leap day: 2024-02-29T12:00:00Z = 1709208000 */
    epoch_to_iso8601(1709208000UL, out, sizeof(out));
    CHECK_STR(out, "2024-02-29T12:00:00Z", "epoch_to_iso8601 leap day");
}

static void test_json_escape(void) {
    char out[64];
    json_escape("plain", out, sizeof(out));
    CHECK_STR(out, "plain", "json_escape plain");

    json_escape("a\"b\\c", out, sizeof(out));
    CHECK_STR(out, "a\\\"b\\\\c", "json_escape quote+backslash");

    json_escape("line1\nline2\t.", out, sizeof(out));
    CHECK_STR(out, "line1\\nline2\\t.", "json_escape newline+tab");

    /* control char (0x01) is dropped */
    char in[4] = { 'a', 0x01, 'b', 0 };
    json_escape(in, out, sizeof(out));
    CHECK_STR(out, "ab", "json_escape drops control char");

    /* NULL input -> empty string, no crash */
    json_escape(NULL, out, sizeof(out));
    CHECK_STR(out, "", "json_escape NULL safe");
}

static void test_traceon_log_builds_iso_json(void) {
    g_publish_calls = 0;
    g_fake_epoch = 1791359800UL;          /* 2026-10-07T07:56:40Z */
    traceon_log("SensorTask", TRACEON_SEV_WARN, "humidity high");

    CHECK(g_publish_calls == 1, "traceon_log publishes once");
    const char* expect =
        "{\"timestamp\":\"2026-10-07T07:56:40Z\","
        "\"context\":{\"type\":\"AUTOSAR_DLT\","
        "\"application_id\":\"TRAC\",\"context_id\":\"SensorTask\","
        "\"session\":\"\",\"session_id\":\"\",\"message_id\":\"\"},"
        "\"severity\":\"DLT_WARN\",\"msg\":\"humidity high\"}";
    CHECK_STR(g_last_json, expect, "traceon_log ISO LogEntry JSON");
}

static void test_traceon_log_null_severity_defaults_info(void) {
    g_fake_epoch = 0;
    traceon_log("Main", NULL, "x");
    CHECK(strstr(g_last_json, "\"severity\":\"DLT_INFO\"") != NULL,
          "traceon_log NULL severity -> DLT_INFO");
}

static void test_traceon_log_escapes_msg(void) {
    g_fake_epoch = 0;
    traceon_log("Main", TRACEON_SEV_INFO, "quote\"and\\slash");
    CHECK(strstr(g_last_json, "\\\"and\\\\slash") != NULL,
          "traceon_log escapes msg into JSON");
}

int main(void) {
    printf("== firmware logger host tests ==\n");
    test_epoch_to_iso8601();
    test_json_escape();
    test_traceon_log_builds_iso_json();
    test_traceon_log_null_severity_defaults_info();
    test_traceon_log_escapes_msg();
    if (g_failures == 0) { printf("ALL PASSED\n"); return 0; }
    printf("%d FAILURE(S)\n", g_failures);
    return 1;
}
