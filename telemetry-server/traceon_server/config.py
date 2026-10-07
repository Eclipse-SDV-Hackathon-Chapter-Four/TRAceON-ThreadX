# SPDX-License-Identifier: MIT
# Copyright (c) 2026 the TRAceON team
# Portions of this file were generated with AI assistance.

"""Configuration for the TRAceON telemetry server.

All settings are overridable via environment variables so the server can be
pointed at a different broker/topic without code changes.
"""
from __future__ import annotations

import os
from dataclasses import dataclass


@dataclass(frozen=True)
class Settings:
    # MQTT broker (defaults to the local Docker broker on 1883).
    mqtt_host: str = os.getenv("TRACEON_MQTT_HOST", "localhost")
    mqtt_port: int = int(os.getenv("TRACEON_MQTT_PORT", "1883"))

    # Topics. The device publishes sensor data and logs; we publish commands to it.
    # All derive from the component/team name to stay in sync with the firmware.
    component: str = os.getenv("TRACEON_COMPONENT", "TRAceON")
    sensor_topic: str = os.getenv("TRACEON_SENSOR_TOPIC", "TRAceON/sensor-data")
    log_topic: str = os.getenv("TRACEON_LOG_TOPIC", "TRAceON/logs")
    command_topic: str = os.getenv("TRACEON_COMMAND_TOPIC", "TRAceON/incoming")

    # How many recent entries to retain in the in-memory ring buffers.
    log_buffer_size: int = int(os.getenv("TRACEON_LOG_BUFFER_SIZE", "100"))
    telemetry_history_size: int = int(os.getenv("TRACEON_TELEMETRY_HISTORY_SIZE", "100"))

    # Optional downstream log sink: when forwarding is started, each received
    # LogEntry is POSTed to this URL. Forwarding is a separate function from SSE
    # and is OFF until started via the control endpoint.
    log_forward_url: str = os.getenv("TRACEON_LOG_FORWARD_URL", "")

    # HTTP server bind.
    http_host: str = os.getenv("TRACEON_HTTP_HOST", "0.0.0.0")
    http_port: int = int(os.getenv("TRACEON_HTTP_PORT", "8083"))


settings = Settings()
