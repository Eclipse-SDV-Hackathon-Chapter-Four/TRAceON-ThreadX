"""Optional log forwarding sink.

A SEPARATE function from SSE: when started, each received LogEntry is POSTed to
`TRACEON_LOG_FORWARD_URL`. Fire-and-forget on a background worker thread so it
never blocks MQTT ingestion or the SSE path. Start/stop is controlled at runtime
via the API; it is OFF by default.

Uses stdlib urllib (no extra dependency).
"""
from __future__ import annotations

import json
import logging
import queue
import threading
import urllib.request
from typing import Any

from .config import settings

logger = logging.getLogger("traceon.forward")


class LogForwarder:
    def __init__(self, url: str) -> None:
        self._url = url
        self._enabled = False
        self._queue: queue.Queue[dict[str, Any]] = queue.Queue(maxsize=1000)
        self._worker: threading.Thread | None = None
        self._stop = threading.Event()
        self._lock = threading.Lock()
        # Counters for status.
        self.forwarded = 0
        self.failed = 0
        self.dropped = 0

    @property
    def url(self) -> str:
        return self._url

    def set_url(self, url: str) -> None:
        with self._lock:
            self._url = url

    @property
    def enabled(self) -> bool:
        return self._enabled

    def start(self) -> None:
        with self._lock:
            if self._enabled:
                return
            if not self._url:
                raise ValueError("No forward URL configured (set TRACEON_LOG_FORWARD_URL)")
            self._enabled = True
            self._stop.clear()
            self._worker = threading.Thread(
                target=self._run, name="log-forwarder", daemon=True
            )
            self._worker.start()
            logger.info("Log forwarding started -> %s", self._url)

    def stop(self) -> None:
        with self._lock:
            if not self._enabled:
                return
            self._enabled = False
            self._stop.set()
            logger.info("Log forwarding stopped")

    def submit(self, entry: dict[str, Any]) -> None:
        """Called from the MQTT thread. Non-blocking; drops if the queue is full."""
        if not self._enabled:
            return
        try:
            self._queue.put_nowait(entry)
        except queue.Full:
            self.dropped += 1

    def status(self) -> dict[str, Any]:
        return {
            "enabled": self._enabled,
            "url": self._url or None,
            "forwarded": self.forwarded,
            "failed": self.failed,
            "dropped": self.dropped,
            "queued": self._queue.qsize(),
        }

    def _run(self) -> None:
        while not self._stop.is_set():
            try:
                entry = self._queue.get(timeout=0.5)
            except queue.Empty:
                continue
            self._post(entry)

    def _post(self, entry: dict[str, Any]) -> None:
        data = json.dumps(entry).encode("utf-8")
        req = urllib.request.Request(
            self._url, data=data,
            headers={"Content-Type": "application/json"}, method="POST",
        )
        try:
            with urllib.request.urlopen(req, timeout=5) as resp:
                resp.read()
            self.forwarded += 1
        except Exception as exc:  # noqa: BLE001 - best-effort fire-and-forget
            self.failed += 1
            logger.warning("Log forward POST failed: %s", exc)


# Module-level singleton (URL from env; OFF until started).
forwarder = LogForwarder(settings.log_forward_url)
