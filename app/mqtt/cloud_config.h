/* 
 * Copyright (c) Microsoft
 * Copyright (c) 2024 Eclipse Foundation
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
 *     Microsoft         - Initial version
 *     Frédéric Desbiens - 2024 version.
 */

#ifndef _CLOUD_CONFIG_H
#define _CLOUD_CONFIG_H

#include "nx_api.h"

typedef enum
{
    None         = 0,
    WEP          = 1,
    WPA_PSK_TKIP = 2,
    WPA2_PSK_AES = 3
} WiFi_Mode;

// ----------------------------------------------------------------------------
// WiFi connection config
// ----------------------------------------------------------------------------
// These can be overridden at BUILD time (so you don't edit this file per
// network). Pass them via scripts/build.sh env vars, e.g.:
//   WIFI_SSID='MyNet' WIFI_PASSWORD='secret' BROKER_IP=192.168.1.10 ./scripts/build.sh mqtt clean
// Each falls back to the default below if not provided on the compiler cmdline.
#define HOSTNAME      "eclipse-threadx"  //Change to unique hostname.
#ifndef WIFI_SSID
#define WIFI_SSID     "Hackathon-Team-11"
#endif
#ifndef WIFI_PASSWORD
#define WIFI_PASSWORD "SDVTeam-123456"
#endif
#define WIFI_MODE     WPA2_PSK_AES

// ----------------------------------------------------------------------------
// MQTT Config
// ----------------------------------------------------------------------------
#ifndef MQTT_CLIENT_NAME
#define MQTT_CLIENT_NAME     "TRAceON" //Change to unique name.
#endif
// Broker IP. Override at build time by passing the four octets as
// MQTT_BROKER_O1..O4 (-D), done for you by build.sh from BROKER_IP=a.b.c.d.
#ifndef MQTT_BROKER_O1
#define MQTT_BROKER_O1 192
#define MQTT_BROKER_O2 168
#define MQTT_BROKER_O3 88
#define MQTT_BROKER_O4 254
#endif
#define MQTT_LOCAL_BROKER_IP (IP_ADDRESS(MQTT_BROKER_O1, MQTT_BROKER_O2, MQTT_BROKER_O3, MQTT_BROKER_O4))
#define MQTT_SUBSCRIBE_TOPIC MQTT_CLIENT_NAME "/incoming" 
#define MQTT_PUBLISH_TOPIC   MQTT_CLIENT_NAME "/sensor-data" 
#define MQTT_LOG_TOPIC       MQTT_CLIENT_NAME "/logs"

// ----------------------------------------------------------------------------
// MQTT Support infrastructure
// ----------------------------------------------------------------------------
extern TX_EVENT_FLAGS_GROUP mqtt_app_flag;
#define MQTT_RECEIVE_EVENT 1
#define MQTT_MESSAGE_READY 2
/* A log entry has been enqueued for the MQTT thread to publish (C1: only the
 * MQTT thread touches the NXD_MQTT_CLIENT). */
#define MQTT_LOG_READY     8
#define MQTT_ALL_EVENTS    (MQTT_RECEIVE_EVENT | MQTT_MESSAGE_READY | MQTT_LOG_READY)
/* Set by the MQTT thread while connected, cleared on disconnect. The telemetry
 * thread checks this before requesting a publish, so nothing is published
 * before the client is connected (or while it is down). */
#define MQTT_CONNECTED     4


#endif // _CLOUD_CONFIG_H