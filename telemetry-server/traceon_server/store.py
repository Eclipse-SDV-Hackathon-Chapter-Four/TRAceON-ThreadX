# SPDX-FileCopyrightText: Copyright (c) 2026 Contributors to the Eclipse Foundation
# SPDX-License-Identifier: MIT
# Portions of this file were generated with AI assistance.

"""Thread-safe in-memory store for the latest telemetry reading.

The MQTT client thread writes; the HTTP (async) handlers read. A simple lock
keeps it consistent. Only the latest reading is kept (history is a future
enhancement — see telemetry-server-PLAN.md).
"""
from __future__ import annotations

import threading
import time
from collections import deque
from typing import Any

from .config import settings


class TelemetryStore:
    def __init__(self) -> None:
        self._lock = threading.Lock()
        self._fields: dict[str, Any] = {}
        self._received_at: float | None = None
        self._raw: str | None = None
        self._message_count: int = 0
        # Ring buffer of the last N readings: each item is {received_at, fields}.
        self._history: deque[dict[str, Any]] = deque(maxlen=settings.telemetry_history_size)

    def update(self, fields: dict[str, Any], raw: str) -> None:
        with self._lock:
            self._fields = dict(fields)
            self._raw = raw
            self._received_at = time.time()
            self._message_count += 1
            self._history.append(
                {"received_at": self._received_at, "fields": dict(fields)}
            )

    def latest(self) -> dict[str, Any]:
        """Return the latest reading plus metadata (empty fields if none yet)."""
        with self._lock:
            age = None
            if self._received_at is not None:
                age = round(time.time() - self._received_at, 3)
            return {
                "fields": dict(self._fields),
                "received_at": self._received_at,
                "age_seconds": age,
                "message_count": self._message_count,
            }

    def get_field(self, name: str) -> Any | None:
        with self._lock:
            return self._fields.get(name)

    def field_names(self) -> list[str]:
        with self._lock:
            return list(self._fields.keys())

    def has_data(self) -> bool:
        with self._lock:
            return self._received_at is not None

    def history(self) -> list[dict[str, Any]]:
        """Snapshot copy of the reading history ring buffer (oldest first)."""
        with self._lock:
            return [{"received_at": e["received_at"], "fields": dict(e["fields"])}
                    for e in self._history]


# Module-level singleton shared between the MQTT client and the HTTP app.
store = TelemetryStore()
