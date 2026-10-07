# SPDX-License-Identifier: MIT
# Copyright (c) 2026 the TRAceON team
# Portions of this file were generated with AI assistance.

"""Log ingestion for TRAceON — ISO 17978-3 LogEntry (Table 316).

The board publishes JSON log entries on `TRAceON/logs`:

    {
      "timestamp": "2026-10-06T16:51:06Z",            # ISO-8601 date-time
      "context":   { "type": "AUTOSAR_DLT",           # Context (typed object)
                     "application_id": "TRAC", "context_id": "SensorTask",
                     "session": "", "session_id": "", "message_id": "" },
      "severity":  "DLT_WARN",                        # Severity (DLT_* level)
      "msg":       "Humidity sensor returned no data"
    }

The ring buffer stores entries ISO-pure (exactly timestamp/context/severity/msg —
no seq/received_at). A server-side receive time is tracked separately (not part of
the LogEntry) so history time-filtering still works. Legacy bare severities
(WARN/ERROR/...) are mapped to DLT_* defensively.
"""
from __future__ import annotations

import json
import threading
import time
from collections import deque
from typing import Any

from .config import settings

# Map legacy bare severities to DLT_* (defensive; the board now emits DLT_* directly).
_LEGACY_SEVERITY = {
    "FATAL": "DLT_FATAL",
    "ERROR": "DLT_ERROR",
    "WARN": "DLT_WARN",
    "WARNING": "DLT_WARN",
    "INFO": "DLT_INFO",
    "DEBUG": "DLT_DEBUG",
    "VERBOSE": "DLT_VERBOSE",
}
_DLT_LEVELS = set(_LEGACY_SEVERITY.values())


def _normalize_severity(value: Any) -> str:
    s = str(value or "").strip()
    if s in _DLT_LEVELS:
        return s
    up = s.upper()
    if up in _LEGACY_SEVERITY:
        return _LEGACY_SEVERITY[up]
    return "DLT_INFO" if not s else s


def parse_log(payload: str) -> dict[str, Any]:
    """Parse a raw JSON payload into an ISO LogEntry dict
    {timestamp, context, severity, msg}. Lenient fallback on malformed input."""
    try:
        obj = json.loads(payload)
        if isinstance(obj, dict):
            return {
                "timestamp": str(obj.get("timestamp", "")),
                "context": obj.get("context") if isinstance(obj.get("context"), dict)
                else {"type": "AUTOSAR_DLT", "context_id": str(obj.get("context", ""))},
                "severity": _normalize_severity(obj.get("severity")),
                "msg": str(obj.get("msg", "")),
            }
    except (ValueError, TypeError):
        pass
    # Fallback: not valid JSON — keep the raw text visible.
    return {
        "timestamp": "",
        "context": {"type": "AUTOSAR_DLT", "context_id": ""},
        "severity": "DLT_INFO",
        "msg": payload.strip(),
    }


def context_id_of(entry: dict[str, Any]) -> str:
    """Extract the AUTOSAR_DLT context_id (used for history filtering)."""
    ctx = entry.get("context")
    if isinstance(ctx, dict):
        return str(ctx.get("context_id", ""))
    return str(ctx or "")


class LogStore:
    """Thread-safe bounded ring buffer of ISO LogEntry objects.

    Each slot keeps the ISO-pure entry plus a private server-side receive time
    (for history time-filtering only; not part of the LogEntry payload)."""

    def __init__(self, maxlen: int) -> None:
        self._lock = threading.Lock()
        self._buf: deque[dict[str, Any]] = deque(maxlen=maxlen)
        self._count = 0

    def add(self, entry: dict[str, Any]) -> dict[str, Any]:
        with self._lock:
            self._count += 1
            slot = {"received_at": time.time(), "entry": entry}  # entry = ISO LogEntry
            self._buf.append(slot)
            return entry

    def snapshot(self) -> list[dict[str, Any]]:
        """Internal slots (entry + received_at), oldest first."""
        with self._lock:
            return list(self._buf)

    def entries(self) -> list[dict[str, Any]]:
        """Plain list of ISO LogEntry objects (ISO-pure)."""
        with self._lock:
            return [s["entry"] for s in self._buf]

    def count(self) -> int:
        with self._lock:
            return self._count


log_store = LogStore(settings.log_buffer_size)
