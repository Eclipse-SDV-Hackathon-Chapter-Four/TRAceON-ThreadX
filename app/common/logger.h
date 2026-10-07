/*
 *  Copyright (c) 2026 Contributors to the Eclipse Foundation
 *
 *  This program and the accompanying materials are made available
 *  under the terms of the MIT license which is available at
 *  https://opensource.org/license/mit.
 *
 *  SPDX-License-Identifier: MIT
 *
 *  Portions of this file were generated with AI assistance.
 */

/*
 * TRAceON structured logger — emits ISO 17978-3 LogEntry (Table 316) JSON to the
 * MQTT log topic:
 *
 *   {
 *     "timestamp": "<ISO-8601 UTC>",
 *     "context":   { "type": "AUTOSAR_DLT",
 *                    "application_id": "TRAC", "context_id": "<ctx>",
 *                    "session": "", "session_id": "", "message_id": "" },
 *     "severity":  "DLT_WARN",
 *     "msg":       "..."
 *   }
 *
 * The subsystem name passed by callers maps to the AUTOSAR_DLT `context_id`.
 * Severity uses the DLT levels. Mirrors each line to the serial console.
 */

#ifndef _LOGGER_H
#define _LOGGER_H

/* ISO Severity values (AUTOSAR DLT log levels). */
#define TRACEON_SEV_FATAL   "DLT_FATAL"
#define TRACEON_SEV_ERROR   "DLT_ERROR"
#define TRACEON_SEV_WARN    "DLT_WARN"
#define TRACEON_SEV_INFO    "DLT_INFO"
#define TRACEON_SEV_DEBUG   "DLT_DEBUG"
#define TRACEON_SEV_VERBOSE "DLT_VERBOSE"

/*
 * Publish a log entry.
 *   context_id - subsystem/task name -> AUTOSAR_DLT context_id (NULL -> "")
 *   severity   - one of TRACEON_SEV_* (NULL -> DLT_INFO)
 *   msg        - the human-readable message (NULL -> "")
 */
void traceon_log(const char* context_id, const char* severity, const char* msg);

/* Convenience wrappers (severity preset). */
void traceon_log_fatal(const char* context_id, const char* msg);
void traceon_log_error(const char* context_id, const char* msg);
void traceon_log_warn(const char* context_id, const char* msg);
void traceon_log_info(const char* context_id, const char* msg);
void traceon_log_debug(const char* context_id, const char* msg);
void traceon_log_verbose(const char* context_id, const char* msg);

#endif // _LOGGER_H