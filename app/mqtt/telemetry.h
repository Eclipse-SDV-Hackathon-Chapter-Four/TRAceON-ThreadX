/* 
 *  Copyright (c) 2025 Eclipse Foundation
 * 
 *  This program and the accompanying materials are made available 
 *  under the terms of the MIT license which is available at
 *  https://opensource.org/license/mit.
 * 
 *  SPDX-License-Identifier: MIT
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

void telemetry_thread_entry(ULONG parameter);
void get_current_telemetry_string(char* output);

#endif // _TELEMETRY_H