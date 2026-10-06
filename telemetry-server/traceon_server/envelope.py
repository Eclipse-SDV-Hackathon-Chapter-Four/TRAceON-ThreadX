"""ISO 17978-3 EventEnvelope (Table 5) used to wrap events on SSE streams.

    { "timestamp": "<server emit time, ISO-8601>",
      "payload":   <AnyValue>,     # e.g. a LogEntry
      "error":     <string|null> } # GenericError (string here); null on success
"""
from __future__ import annotations

from datetime import datetime, timezone
from typing import Any


def _now_iso() -> str:
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def event_envelope(payload: Any, error: str | None = None) -> dict[str, Any]:
    """Wrap a payload in an EventEnvelope with the server emit time."""
    return {"timestamp": _now_iso(), "payload": payload, "error": error}
