# SPDX-License-Identifier: MIT
# Copyright (c) 2026 the TRAceON team
# Portions of this file were generated with AI assistance.

"""Thread -> async bridge for SSE streaming.

The MQTT client runs its network loop in a background THREAD, while FastAPI
handlers are async on the event loop. This broadcaster lets the thread hand new
items to async SSE subscribers safely.

There are two independent channels: "telemetry" and "logs". Each SSE client gets
its own bounded asyncio.Queue; slow clients drop oldest items rather than block
the publisher.
"""
from __future__ import annotations

import asyncio
from typing import Any


class Broadcaster:
    def __init__(self) -> None:
        self._loop: asyncio.AbstractEventLoop | None = None
        # channel name -> set of subscriber queues
        self._subscribers: dict[str, set[asyncio.Queue]] = {
            "telemetry": set(),
            "logs": set(),
        }

    def set_loop(self, loop: asyncio.AbstractEventLoop) -> None:
        """Called once at startup (from the event loop) so the MQTT thread can
        schedule work onto it."""
        self._loop = loop

    def subscribe(self, channel: str) -> asyncio.Queue:
        q: asyncio.Queue = asyncio.Queue(maxsize=100)
        self._subscribers[channel].add(q)
        return q

    def unsubscribe(self, channel: str, q: asyncio.Queue) -> None:
        self._subscribers[channel].discard(q)

    def publish(self, channel: str, item: Any) -> None:
        """Publish from ANY thread. Safe to call from the MQTT callback thread."""
        loop = self._loop
        if loop is None:
            return
        loop.call_soon_threadsafe(self._deliver, channel, item)

    def _deliver(self, channel: str, item: Any) -> None:
        # Runs on the event loop thread.
        for q in list(self._subscribers.get(channel, ())):
            if q.full():
                try:
                    q.get_nowait()  # drop oldest for slow consumers
                except asyncio.QueueEmpty:
                    pass
            q.put_nowait(item)


# Module-level singleton.
broadcaster = Broadcaster()
