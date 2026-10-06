"""FastAPI application for the TRAceON telemetry server.

Two route surfaces:

1. Simple/native (for our own dashboard & debugging):
     GET  /health
     GET  /telemetry/latest
     GET  /telemetry/latest/{field}
     POST /command

2. SOVD-flavored (ISO 17978 shape) so a future Eclipse OpenSOVD gateway can map
   onto it with minimal glue. SOVD models data as resources under an entity:
     GET  /components/{component}/data
     GET  /components/{component}/data/{resource_id}

The MQTT client runs in the background and keeps `store` up to date.
"""
from __future__ import annotations

import asyncio
import json
import logging
from contextlib import asynccontextmanager

from fastapi import FastAPI, HTTPException, Request
from fastapi.responses import StreamingResponse
from pydantic import BaseModel

from .config import settings
from .mqtt_client import mqtt_client
from .store import store
from .logs import log_store
from .broadcaster import broadcaster
from . import query

logging.basicConfig(level=logging.INFO)


@asynccontextmanager
async def lifespan(app: FastAPI):
    # Capture the running event loop so the MQTT thread can push to SSE queues.
    broadcaster.set_loop(asyncio.get_running_loop())
    # Start MQTT on startup, stop on shutdown.
    mqtt_client.start()
    yield
    mqtt_client.stop()


app = FastAPI(
    title="TRAceON Telemetry Server",
    description="Serves AZ3166 sensor telemetry (via MQTT) over HTTP, with a "
    "SOVD-flavored read surface for future OpenSOVD integration.",
    version="0.1.0",
    lifespan=lifespan,
)


# --------------------------------------------------------------------------
# Models
# --------------------------------------------------------------------------
class CommandRequest(BaseModel):
    message: str


# --------------------------------------------------------------------------
# Simple / native surface
# --------------------------------------------------------------------------
@app.get("/health", tags=["simple"])
def health():
    latest = store.latest()
    return {
        "status": "ok",
        "mqtt_connected": mqtt_client.connected,
        "has_data": store.has_data(),
        "last_message_age_seconds": latest["age_seconds"],
        "message_count": latest["message_count"],
    }


@app.get("/telemetry/latest/{field}", tags=["simple"])
def telemetry_field(field: str):
    value = store.get_field(field)
    if value is None:
        raise HTTPException(
            status_code=404,
            detail=f"Field '{field}' not available. Known: {store.field_names()}",
        )
    return {"field": field, "value": value}


@app.post("/command", tags=["simple"])
def post_command(cmd: CommandRequest):
    ok = mqtt_client.publish_command(cmd.message)
    if not ok:
        raise HTTPException(status_code=502, detail="Failed to publish command to broker")
    return {"published": True, "topic": settings.command_topic, "message": cmd.message}


# --------------------------------------------------------------------------
# Streaming (SSE) — the telemetry/logs "entries" are live streams by default
# --------------------------------------------------------------------------
async def _sse_event_stream(request: Request, channel: str):
    """Yield text/event-stream frames for a broadcaster channel until the client
    disconnects. Sends periodic keep-alive comments so proxies don't time out."""
    queue = broadcaster.subscribe(channel)
    try:
        while True:
            if await request.is_disconnected():
                break
            try:
                item = await asyncio.wait_for(queue.get(), timeout=15.0)
                yield f"data: {json.dumps(item)}\n\n"
            except asyncio.TimeoutError:
                yield ": keep-alive\n\n"  # SSE comment line
    finally:
        broadcaster.unsubscribe(channel, queue)


@app.get("/telemetry/entries", tags=["stream"])
async def telemetry_entries(request: Request):
    """SSE stream of telemetry readings as they arrive."""
    return StreamingResponse(
        _sse_event_stream(request, "telemetry"),
        media_type="text/event-stream",
    )


@app.get("/logs/entries", tags=["stream"])
async def logs_entries(request: Request):
    """SSE stream of log entries as they arrive."""
    return StreamingResponse(
        _sse_event_stream(request, "logs"),
        media_type="text/event-stream",
    )


# --------------------------------------------------------------------------
# History (ring buffers) — snapshot queries with filters
# --------------------------------------------------------------------------
@app.get("/telemetry/history", tags=["history"])
def telemetry_history(
    limit: int | None = None,
    field: str | None = None,
    since: str | None = None,
    until: str | None = None,
):
    """Last <=100 telemetry readings, filterable.

    - limit: most recent N (default/cap 100)
    - field: return only this field per reading (e.g. temperature_degC)
    - since/until: ISO-8601 bounds on server receive time
    """
    try:
        since_ts = query.parse_iso8601(since)
        until_ts = query.parse_iso8601(until)
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=f"Invalid since/until: {exc}")

    items = store.history()
    items = [e for e in items if query.in_time_range(e["received_at"], since_ts, until_ts)]

    if field is not None:
        projected = []
        for e in items:
            if field in e["fields"]:
                projected.append({"received_at": e["received_at"], "value": e["fields"][field]})
        items = projected

    n = query.clamp_limit(limit)
    items = items[-n:]
    return {"count": len(items), "field": field, "entries": items}


@app.get("/logs/history", tags=["history"])
def logs_history(
    limit: int | None = None,
    severity: str | None = None,
    context: str | None = None,
    since: str | None = None,
    until: str | None = None,
):
    """Last <=100 log entries, filterable.

    - limit: most recent N (default/cap 100)
    - severity: comma-separated list, case-insensitive (e.g. WARN,ERROR)
    - context: exact match (e.g. SensorTask)
    - since/until: ISO-8601 bounds on server receive time
    """
    try:
        since_ts = query.parse_iso8601(since)
        until_ts = query.parse_iso8601(until)
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=f"Invalid since/until: {exc}")

    sev_set = None
    if severity:
        sev_set = {s.strip().upper() for s in severity.split(",") if s.strip()}

    items = log_store.snapshot()  # full buffer; filter then limit
    out = []
    for e in items:
        if not query.in_time_range(e["received_at"], since_ts, until_ts):
            continue
        if sev_set is not None and str(e.get("severity", "")).upper() not in sev_set:
            continue
        if context is not None and e.get("context") != context:
            continue
        out.append(e)

    n = query.clamp_limit(limit)
    out = out[-n:]
    return {"count": len(out), "entries": out}


# --------------------------------------------------------------------------
# SOVD-flavored surface (ISO 17978 resource model)
# --------------------------------------------------------------------------
def _check_component(component: str) -> None:
    if component != settings.component:
        raise HTTPException(status_code=404, detail=f"Unknown component '{component}'")


@app.get("/components/{component}/data", tags=["sovd"])
def sovd_list_data(component: str):
    """List available SOVD 'data' resources for the component."""
    _check_component(component)
    return {
        "component": component,
        "items": [
            {"id": name, "href": f"/components/{component}/data/{name}"}
            for name in store.field_names()
        ],
    }


@app.get("/components/{component}/data/{resource_id}", tags=["sovd"])
def sovd_get_data(component: str, resource_id: str):
    """Return a single SOVD 'data' resource (one telemetry field)."""
    _check_component(component)
    value = store.get_field(resource_id)
    if value is None:
        raise HTTPException(
            status_code=404,
            detail=f"Data resource '{resource_id}' not available. "
            f"Known: {store.field_names()}",
        )
    # SOVD data resources are JSON objects with an id and a data payload.
    return {"id": resource_id, "data": value}
