"""Log ingestion for TRAceON: parse 4-field JSON logs into a ring buffer.

Logs are a SEPARATE stream from telemetry. The board publishes JSON log messages
on `TRAceON/logs` with four string fields:

    {"timestamp": "2026-10-06T15:37:24.607Z",   # ISO-8601 date-time
     "context":   "SensorTask",
     "severity":  "WARN",
     "msg":       "Humidity sensor returned no data"}

Parsing is lenient: a malformed / non-JSON payload still yields an entry (so bad
data is visible rather than silently dropped), with best-effort field extraction.
"""
from __future__ import annotations

import json
import threading
import time
from collections import deque
from typing import Any

from .config import settings


def parse_log(payload: str) -> dict[str, Any]:
    """Parse a raw JSON log payload into {timestamp, context, severity, msg}.

    All four are strings. On malformed input, returns a best-effort entry with
    severity 'UNKNOWN' and the raw payload as msg, so nothing is dropped.
    """
    try:
        obj = json.loads(payload)
        if isinstance(obj, dict):
            return {
                "timestamp": _as_str(obj.get("timestamp")),
                "context": _as_str(obj.get("context")),
                "severity": _as_str(obj.get("severity")),
                "msg": _as_str(obj.get("msg")),
            }
    except (ValueError, TypeError):
        pass
    # Fallback: not valid JSON / not an object.
    return {"timestamp": "", "context": "", "severity": "UNKNOWN", "msg": payload.strip()}


def _as_str(value: Any) -> str:
    return "" if value is None else str(value)


class LogStore:
    """Thread-safe bounded ring buffer of log entries."""

    def __init__(self, maxlen: int) -> None:
        self._lock = threading.Lock()
        self._buf: deque[dict[str, Any]] = deque(maxlen=maxlen)
        self._seq = 0

    def add(self, parsed: dict[str, Any]) -> dict[str, Any]:
        with self._lock:
            self._seq += 1
            entry = {
                "seq": self._seq,
                "received_at": time.time(),  # server-side receipt time (diagnostics)
                "timestamp": parsed["timestamp"],
                "context": parsed["context"],
                "severity": parsed["severity"],
                "msg": parsed["msg"],
            }
            self._buf.append(entry)
            return entry

    def snapshot(self, limit: int | None = None) -> list[dict[str, Any]]:
        with self._lock:
            items = list(self._buf)
        if limit is not None and limit >= 0:
            items = items[-limit:]
        return items

    def count(self) -> int:
        with self._lock:
            return self._seq


# Module-level singleton.
log_store = LogStore(settings.log_buffer_size)
