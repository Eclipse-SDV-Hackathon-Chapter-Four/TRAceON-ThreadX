/* 
 *  Copyright (c) 2025 Eclipse Foundation
 * 
 *  This program and the accompanying materials are made available 
 *  under the terms of the MIT license which is available at
 *  https://opensource.org/license/mit.
 * 
 *  SPDX-License-Identifier: MIT
 *
 *  Portions of this file were generated with AI assistance.
 * 
 *  Contributors: 
 *     Frédéric Desbiens - Initial version.
 */

#include "cloud_config.h"
#include "logger.h"
#include "mqtt_client.h"
#include "nanoprintf.h" 
#include "sensor.h"
#include "telemetry.h"
#include <stdio.h>

// Refresh interval
static const int32_t telemetry_interval = 5;

// Current data
static sensor_data current_sensor_data;

// Telemetry output. Compile-time constants (not VLAs). TELEMETRY_BUFFER_SIZE
// is defined in telemetry.h and must be >= TELEMETRY_ROWS * TELEMETRY_ROW_SIZE.
#define TELEMETRY_ROWS        5
#define TELEMETRY_ROW_SIZE    40

/* Function to compare two float arrays
 * Returns true if arrays are equal within the given tolerance, otherwise false.
 */
static UINT compare_float_arrays(const float* arr1, const float* arr2, size_t size, float epsilon) {
    if (arr1 == NULL || arr2 == NULL) {
        return 0;
    }

    for (size_t i = 0; i < size; ++i) {
        if (fabsf((float)(arr1[i] - arr2[i])) > epsilon) {
            return 0; // Found a difference outside the tolerance
        }
    }
    return 1; // No significant differences found
}

/**
 * Check if the data set changed between readings.
 * 
 * Proper epsilon values for physical sensor readings depend on the sensor's accuracy. 
 * We picked a margin of error that makes sense for the use case.
 */
static UINT data_changed(sensor_data const * const current_data, sensor_data const * const new_data){
    return !(current_data->pressure_hPa == new_data->pressure_hPa &&
             current_data->temperature_degC == new_data->temperature_degC &&
             current_data->humidity_perc == new_data->humidity_perc &&
             compare_float_arrays(current_data->acceleration_mg, new_data->acceleration_mg, 3, 0.1f) &&
             compare_float_arrays(current_data->magnetic_mG, new_data->magnetic_mG, 3, 1.0f)); // Could also be 5.0f
}

static void get_sensor_data_buffer(sensor_data data, char* output, int output_size){
    char buf[TELEMETRY_ROWS][TELEMETRY_ROW_SIZE];
    npf_snprintf(buf[0], TELEMETRY_ROW_SIZE, "Pressure: %.2f\r\n", (double)data.pressure_hPa);
    npf_snprintf(buf[1], TELEMETRY_ROW_SIZE, "Temperature: %.2f\r\n", (double)data.temperature_degC);
    npf_snprintf(buf[2], TELEMETRY_ROW_SIZE, "Humidity: %.2f\r\n", (double)data.humidity_perc);
    npf_snprintf(buf[3], TELEMETRY_ROW_SIZE, "Acceleration: %.2f, %.2f, %.2f\r\n", 
                                                    (double)data.acceleration_mg[0],
                                                    (double)data.acceleration_mg[1],
                                                    (double)data.acceleration_mg[2]);
    npf_snprintf(buf[4], TELEMETRY_ROW_SIZE, "Magnetic: %.2f, %.2f, %.2f\r\n", 
                                                (double)data.magnetic_mG[0],
                                                (double)data.magnetic_mG[1],
                                                (double)data.magnetic_mG[2]);

    // Bounded concatenation: never write past output_size (incl. NUL).
    if (output_size <= 0) { return; }
    output[0] = '\0';
    int used = 0;  // chars written, excluding NUL
    for (int i = 0; i < TELEMETRY_ROWS; i++) {
        int remaining = output_size - 1 - used;   // space left for chars (keep NUL)
        if (remaining <= 0) { break; }
        for (int j = 0; buf[i][j] != '\0' && remaining > 0; j++, remaining--) {
            output[used++] = buf[i][j];
        }
    }
    output[used] = '\0';
}

/** 
 * Only used if LOG_TELEMETRY is defined. 
 * 
 * Uncomment the definition in telemetry.h if needed.
 */
#ifdef LOG_TELEMETRY
static void print_sensor_data(sensor_data data){
    char data_string[TELEMETRY_BUFFER_SIZE];
    get_sensor_data_buffer(data, data_string, sizeof(data_string));
    printf("=====\r\n");
    printf("%s", data_string);   
    printf("=====\r\n\r\n");
}
#endif

#ifdef DEMO_LOGS
// DEMO MODE: emit one varied log entry per demo tick, cycling through this table
// so the stream shows a realistic mix of severities and contexts. Not meant for
// production — toggle via DEMO_LOGS in telemetry.h.
#define DEMO_LOG_INTERVAL_SEC 1   // emit a demo log this often (seconds)

typedef enum { SEV_INFO, SEV_WARN, SEV_ERROR, SEV_DEBUG, SEV_VERBOSE, SEV_FATAL } demo_sev;

typedef struct {
    demo_sev    severity;
    const char* context_id;
    const char* msg;
} demo_log_entry;

// A rolling script of plausible vehicle/edge events across severities.
static const demo_log_entry demo_log_script[] = {
    { SEV_INFO,    "Main",       "heartbeat" },
    { SEV_DEBUG,   "SensorTask", "sensor poll cycle complete" },
    { SEV_INFO,    "Telemetry",  "telemetry frame published" },
    { SEV_VERBOSE, "I2C",        "bus transaction ok" },
    { SEV_WARN,    "SensorTask", "humidity near upper plausibility bound" },
    { SEV_INFO,    "MQTT",       "broker keep-alive ok" },
    { SEV_ERROR,   "MQTT",       "publish retry after transient error" },
    { SEV_DEBUG,   "Power",      "battery sample nominal" },
    { SEV_WARN,    "Thermal",    "temperature trending high" },
    { SEV_INFO,    "Main",       "system nominal" },
    { SEV_FATAL,   "Watchdog",   "simulated fault (demo only)" },
    { SEV_VERBOSE, "Net",        "DHCP lease still valid" },
};
static const size_t demo_log_script_len =
    sizeof(demo_log_script) / sizeof(demo_log_script[0]);

static void emit_demo_log(size_t idx) {
    const demo_log_entry* e = &demo_log_script[idx % demo_log_script_len];
    switch (e->severity) {
        case SEV_INFO:    traceon_log_info(e->context_id, e->msg);    break;
        case SEV_WARN:    traceon_log_warn(e->context_id, e->msg);    break;
        case SEV_ERROR:   traceon_log_error(e->context_id, e->msg);   break;
        case SEV_DEBUG:   traceon_log_debug(e->context_id, e->msg);   break;
        case SEV_VERBOSE: traceon_log_verbose(e->context_id, e->msg); break;
        case SEV_FATAL:   traceon_log_fatal(e->context_id, e->msg);   break;
    }
}
#endif // DEMO_LOGS

/**
 * Entry point for the telemetry thread.
 */
void telemetry_thread_entry(ULONG parameter)
{
    //UINT status;
    sensor_data new_sensor_data;
#ifdef DEMO_LOGS
    size_t demo_log_index = 0;   // rolls through demo_log_script
#endif

    printf("Starting telemetry thread\r\n\r\n");

    while(1){

        // Acquire fresh data.
        lps22hb_t lps22hb_data = lps22hb_data_read();
        new_sensor_data.temperature_degC = lps22hb_data.temperature_degC;
        new_sensor_data.pressure_hPa = lps22hb_data.pressure_hPa;
        hts221_data_t hts221_data = hts221_data_read();
        new_sensor_data.humidity_perc = hts221_data.humidity_perc;

        // Example plausibility checks -> structured log warnings (TRAceON/logs).
        if (new_sensor_data.humidity_perc <= 0.0f || new_sensor_data.humidity_perc > 100.0f) {
            traceon_log_warn("SensorTask", "Humidity reading out of range (0-100%)");
        }
        if (new_sensor_data.temperature_degC < -40.0f ||
            new_sensor_data.temperature_degC > 85.0f) {
            traceon_log_warn("SensorTask", "Temperature improbable for operating range");
        }
        lsm6dsl_data_t lsm6dsl_data = lsm6dsl_data_read();
        memcpy(new_sensor_data.acceleration_mg, 
               lsm6dsl_data.acceleration_mg,
               sizeof(lsm6dsl_data.acceleration_mg));
        lis2mdl_data_t lis2mdl_data = lis2mdl_data_read();
        memcpy(new_sensor_data.magnetic_mG, 
               lis2mdl_data.magnetic_mG,
               sizeof(lis2mdl_data.magnetic_mG));

        // Publish on EVERY cycle so consumers get regular readings (every
        // telemetry_interval seconds), not only when values change.
        #ifdef LOG_TELEMETRY
            if (data_changed(&current_sensor_data, &new_sensor_data)) {
                printf("Telemetry changed.\r\n");
                print_sensor_data(new_sensor_data);
            } else {
                printf("Telemetry did not change.\r\n");
            }
        #endif
        // Request a telemetry publish only when the MQTT client is connected,
        // so we never signal the MQTT thread to publish before connect / while
        // disconnected.
        if (mqtt_is_connected() == TX_TRUE) {
            tx_event_flags_set(&mqtt_app_flag, MQTT_MESSAGE_READY, TX_OR);
        }
        current_sensor_data = new_sensor_data;

#ifdef DEMO_LOGS
        // DEMO MODE: keep telemetry at telemetry_interval, but emit a varied log
        // every DEMO_LOG_INTERVAL_SEC by sleeping in sub-steps. Cycles through
        // demo_log_script so the stream shows a mix of severities/contexts.
        for (int32_t elapsed = 0; elapsed < telemetry_interval;
             elapsed += DEMO_LOG_INTERVAL_SEC) {
            emit_demo_log(demo_log_index++);
            tx_thread_sleep(TX_TIMER_TICKS_PER_SECOND * DEMO_LOG_INTERVAL_SEC);
        }
#else
        // TEMPORARY (test): emit a heartbeat log every cycle so the board's
        // log path (TRAceON/logs) is exercised on real hardware. Remove later.
        traceon_log_info("Main", "heartbeat");

        tx_thread_sleep(TX_TIMER_TICKS_PER_SECOND * telemetry_interval);
#endif
    }
}

/**
 * Returns current telemetry as a string.
 */
void get_current_telemetry_string(char* output, int output_size){
    get_sensor_data_buffer(current_sensor_data, output, output_size);
}