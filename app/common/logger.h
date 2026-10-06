/*
 *  Copyright (c) 2026 Contributors to the Eclipse Foundation
 *
 *  This program and the accompanying materials are made available
 *  under the terms of the MIT license which is available at
 *  https://opensource.org/license/mit.
 *
 *  SPDX-License-Identifier: MIT
 */

/*
 * TRAceON structured logger.
 *
 * Publishes a 4-field JSON log message to the MQTT log topic, matching the
 * schema expected by the TRAceON telemetry servers:
 *
 *   {"timestamp":"<ISO-8601 UTC>","context":"...","severity":"...","msg":"..."}
 *
 * The timestamp is derived from SNTP (UTC). Also mirrors each line to the serial
 * console. Severity is a free string; the TRACEON_SEV_* constants are the
 * conventional values (DEBUG/INFO/WARN/ERROR).
 */

#ifndef _LOGGER_H
#define _LOGGER_H

#define TRACEON_SEV_DEBUG "DEBUG"
#define TRACEON_SEV_INFO  "INFO"
#define TRACEON_SEV_WARN  "WARN"
#define TRACEON_SEV_ERROR "ERROR"

/*
 * Publish a log message.
 *   context  - subsystem/task name, e.g. "SensorTask" (NULL -> "")
 *   severity - one of TRACEON_SEV_* (NULL -> "INFO")
 *   msg      - the human-readable message (NULL -> "")
 */
void traceon_log(const char* context, const char* severity, const char* msg);

/* Convenience wrappers. */
void traceon_log_debug(const char* context, const char* msg);
void traceon_log_info(const char* context, const char* msg);
void traceon_log_warn(const char* context, const char* msg);
void traceon_log_error(const char* context, const char* msg);

#endif // _LOGGER_H
