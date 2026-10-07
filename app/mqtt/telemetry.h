/* 
 *  Copyright (c) 2025 Eclipse Foundation
 * 
 *  This program and the accompanying materials are made available 
 *  under the terms of the MIT license which is available at
 *  https://opensource.org/license/mit.
 * 
 *  SPDX-License-Identifier: MIT
 *
 *  Copyright (c) 2026 the TRAceON team
 *  Portions of this file were generated with AI assistance.
 * 
 *  Contributors: 
 *     Frédéric Desbiens - Initial version.
 */
#include "sensor.h"
#include "tx_api.h"
#include <math.h>
#include <stdint.h>
#include <stdio.h>

#ifndef _TELEMETRY_H
#define _TELEMETRY_H

// Log telemetry if needed
#define LOG_TELEMETRY

// DEMO MODE: when defined, the telemetry thread emits a frequent, varied stream
// of log entries (cycling severities/contexts) in addition to publishing
// telemetry. Handy for demos/stress-testing the log path. Comment out to return
// to the normal low-rate behaviour. See DEMO_LOG_INTERVAL_SEC in telemetry.c.
#define DEMO_LOGS

// Sensor data
typedef struct{
    float pressure_hPa;
    float temperature_degC;
    float humidity_perc;
    float acceleration_mg[3];
    float magnetic_mG[3];
} sensor_data;

// Size of the plain-text telemetry string (5 rows * 40 chars + headroom).
// Compile-time constant so callers can size stack buffers from it.
#define TELEMETRY_BUFFER_SIZE 256

void telemetry_thread_entry(ULONG parameter);
void get_current_telemetry_string(char* output, int output_size);

#endif // _TELEMETRY_H