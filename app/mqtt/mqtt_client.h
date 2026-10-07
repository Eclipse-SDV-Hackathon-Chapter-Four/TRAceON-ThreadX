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
 
#include "nxd_mqtt_client.h"

#ifndef MQTT_CLIENT_H
#define MQTT_CLIENT_H
/* Declare event flag, which is used in this demo. */
#endif

#undef  NXD_MQTT_MAX_TOPIC_NAME_LENGTH
#undef  NXD_MQTT_MAX_MESSAGE_LENGTH
#define NXD_MQTT_MAX_TOPIC_NAME_LENGTH 70
/* ISO LogEntry (AUTOSAR_DLT context object) is larger than the old flat log,
 * so allow a bigger payload. Keep in sync with logger.c LOG_JSON_MAX. */
#define NXD_MQTT_MAX_MESSAGE_LENGTH 320

#define MQTT_CLIENT_STACK_SIZE 5120


void mqtt_thread_entry(ULONG thread_input);

/* Returns TX_TRUE while the MQTT client is connected to the broker (tracks the
 * MQTT_CONNECTED event-flag bit). Used to gate publishing. */
UINT mqtt_is_connected(void);

/* Publish a pre-formatted log JSON payload to the MQTT log topic.
 * Returns NXD_MQTT_SUCCESS on success, or an error code if not connected /
 * the publish fails. */
UINT mqtt_publish_log(const char* json, UINT length);

/* Define the symbol for signaling a received message. */

/* Define the priority of the MQTT internal thread. */
#define MQTT_THREAD_PRIORTY 2

/* Define the MQTT keep alive timer for 5 minutes */
#define MQTT_KEEP_ALIVE_TIMER 300

#define QOS0 0
#define QOS1 1
