# AZ3166 Telemetry HTTP Server — Planning Document

> Status: **MVP IMPLEMENTED & VERIFIED** (2026-10-06). Code in `telemetry-server/`.
> Created: 2026-10-06

## Decisions made (2026-10-06)

- **Stack:** Python + FastAPI + `paho-mqtt` (+ uvicorn). Auto OpenAPI docs at `/docs`.
- **Broker:** the Docker `traceon-broker` on `localhost:1883` (see `../BUILD_AND_RUN.md`).
- **Topic:** subscribe `TRAceON/sensor-data`; publish commands to `TRAceON/incoming`.
- **Payload:** parsed **server-side** from the board's plain text → JSON (no firmware change).
- **Routes:** both a simple/native surface AND a **SOVD-flavored** surface (ISO 17978 shape)
  because an **Eclipse OpenSOVD** gateway is expected to connect later.
- **Location:** `~/repos/IEH/TRAceON-ThreadX/telemetry-server/` (self-contained, own venv).
- **Verified:** venv install OK; server connects to broker, receives live board telemetry,
  all routes return correct JSON; 404s correct; `/docs` served.

## Goal

A small HTTP server running on the Mac that **subscribes to the AZ3166's MQTT
telemetry** (published by the `mqtt` sample) and **exposes it over HTTP** so
browsers / tools / dashboards can read the latest sensor data.

## Context (what we already have)

- **Broker:** `eclipse-mosquitto:2` in Docker (container `traceon-broker`). Config:
  `scripts/mosquitto-docker.conf` (listener `0.0.0.0:1883`, `allow_anonymous true`).
  Docker port-publishing bypasses the managed-Mac firewall. See `../BUILD_AND_RUN.md`.
- **Mac LAN IP:** `192.168.88.254` (interface `en0`) — the AZ3166 must target this
  as its broker (`MQTT_LOCAL_BROKER_IP` in `app/mqtt/cloud_config.h`).
- **Device → broker topic:** `TRAceON/sensor-data` (publish).
  Subscribe/incoming topic: `TRAceON/incoming`.
  (From `app/mqtt/cloud_config.h`: `MQTT_CLIENT_NAME = "TRAceON"`,
  `MQTT_PUBLISH_TOPIC = MQTT_CLIENT_NAME "/telemetry"`.)

## Payload shape (IMPORTANT — current format is plain text, not JSON)

The device publishes a human-readable multi-line string built by
`get_sensor_data_buffer()` (QoS1, max ~160 bytes):

```
Pressure: 1013.25
Temperature: 23.40
Humidity: 41.20
Acceleration: 1.20, -0.30, 980.10
Magnetic: 120.00, -45.00, 310.00
```

Design implication / OPEN DECISION:
- Either the HTTP server **parses** this text into structured fields, OR
- We change the firmware to **publish JSON** (cleaner, but a firmware change —
  `telemetry.c get_sensor_data_buffer()`). Recommend starting with server-side
  parsing to avoid touching the device, and consider JSON later.

## Architecture (proposed)

```
AZ3166 ──MQTT publish (TRAceON/sensor-data)──▶ mosquitto (Mac :1883)
                                                        │ subscribe
                                                        ▼
                                             HTTP server (Mac)
                                             - holds latest reading(s)
                                             - optional history buffer
                                             - serves HTTP routes ◀── browser / curl / dashboard
```

- The server is both an **MQTT subscriber** and an **HTTP server** in one process.
- Keep an in-memory "latest telemetry" snapshot updated on every MQTT message.
- Optional: a bounded in-memory ring buffer for recent history.

## Technology — OPEN DECISION
Candidates (pick later):
- **Python**: `paho-mqtt` + `FastAPI`/`Flask`. Fast to write; user reads Python well.
- **Node.js**: `mqtt` + `express`.
- **Go**: `paho.mqtt.golang` + `net/http`. Single binary.
Recommendation lean: **Python + FastAPI + paho-mqtt** (least friction, good JSON story,
auto OpenAPI docs). To be confirmed.

## Routes — TO BE DECIDED (placeholder sketch, NOT final)

These are candidate endpoints to refine together:

| Method | Route (candidate)        | Purpose (candidate)                                  |
|--------|--------------------------|------------------------------------------------------|
| GET    | `/health`                | Liveness + MQTT connection status                    |
| GET    | `/telemetry/latest`      | Latest full reading as JSON                           |
| GET    | `/telemetry/latest/{field}` | Single field (temperature, pressure, humidity, ...) |
| GET    | `/telemetry/history`     | Recent readings (if history buffer implemented)      |
| GET    | `/telemetry/stream`      | Server-Sent Events / websocket live push             |
| GET    | `/` or `/dashboard`      | Minimal HTML page showing live values                |
| POST   | `/command`               | Publish to `TRAceON/incoming` (device control) |

> ⚠️ Routes above are a starting point only — finalize before implementing.

## Features to implement (backlog)

### MVP
- [x] MQTT subscriber: connect to `localhost:1883`, subscribe `TRAceON/sensor-data`.
- [x] Parse the plain-text payload into structured fields
      (pressure_hPa, temperature_degC, humidity_perc, acceleration_mg[3], magnetic_mG[3]).
- [x] Hold latest reading in memory (thread-safe).
- [x] `GET /telemetry/latest` → JSON of the latest reading + a timestamp of receipt.
- [x] `GET /health` → `{status, mqtt_connected, last_message_age_s}`.
- [x] Config (broker host/port, topic, HTTP port) via env vars or a small config file.

### Nice-to-have
- [ ] Bounded in-memory history ring buffer + `GET /telemetry/history?limit=N`.
- [ ] Live push: SSE endpoint for a browser dashboard — see "Streaming design" below.
- [ ] Minimal HTML dashboard page.
- [ ] `POST /command` → publish to `TRAceON/incoming` (round-trip control;
      note firmware currently only reads first byte of incoming message).
- [ ] Per-field endpoints.
- [ ] Basic metrics (messages received, parse errors).

### Later / stretch
- [ ] Persist history (SQLite) instead of memory-only.
- [ ] Switch firmware payload to JSON; drop server-side text parsing.
- [ ] Auth on the HTTP side if exposed beyond localhost.
- [ ] Dockerize the server.

## Streaming design (IMPLEMENTED in both servers — 2026-10-06)

Done: both servers stream via **Server-Sent Events (SSE)** and expose a **logs**
capability (separate from telemetry).

- **Logs** come from the board on `TRAceON/logs` as **JSON** with four string fields:
  `{timestamp (ISO-8601 date-time), context, severity, msg}` (e.g. warnings about
  missing sensor data / improbable values). The server adds `seq` + a server-side
  receipt time, keeps them in a bounded ring buffer (default 200), and falls back to
  `severity:"UNKNOWN"` (raw as `msg`) on malformed payloads. NOT derived from telemetry.
- **Endpoints (both servers):** streaming is the default interface for the collections.
  - `GET /telemetry/entries` (SSE stream), `GET /logs/entries` (SSE stream)
  - Standalone snapshots removed (`/telemetry/latest`, `/logs`). One-shot reads use
    `GET /telemetry/latest/{field}` or the SOVD `GET /components/.../data(/{id})` routes.
  - **History (ring buffers, last 100, implemented 2026-10-06):**
    `GET /telemetry/history` (limit, field, since, until) and
    `GET /logs/history` (limit, severity[csv], context, since, until). `since`/`until`
    are ISO-8601 and filter on the **server receive time**; invalid values → HTTP 400.
    Buffers default/cap 100 (env: `TRACEON_LOG_BUFFER_SIZE`, `TRACEON_TELEMETRY_HISTORY_SIZE`).
- **Python (FastAPI):** `broadcaster.py` bridges the paho thread → asyncio via
  `loop.call_soon_threadsafe`; SSE served with `StreamingResponse(text/event-stream)`.
- **Java (Jakarta):** SSE served by **plain Jakarta async servlets** (`SseServlet` +
  `SseRegistry`), NOT JAX-RS SSE — Jersey's SSE fails to detect async on embedded Jetty.
  JSON routes remain on Jersey + Yasson (JSON-B).

Original design notes (kept for reference):

### Chosen approach: Server-Sent Events (SSE)
- One-way (server → client) over plain HTTP; ideal since commands already go via
  `POST /command`. Browser side is trivial: `new EventSource('/telemetry/stream')`,
  with built-in auto-reconnect. Works through most proxies.
- Alternative considered: **WebSocket** (full-duplex, native in FastAPI/Starlette) —
  rejected for now because telemetry is one-way; more moving parts than needed.
- Alternative: **polling** `GET /telemetry/latest` — works today, no code; not "real" streaming.

### The key technical requirement: thread → async bridge
The MQTT client runs its network loop in a **background thread** (paho `loop_start`),
but FastAPI handlers are **async** on the event loop. New readings must cross from the
thread to the async world safely:
- Add a small **broadcaster** (set of `asyncio.Queue`s), e.g. in a new `events.py` or on
  the store.
- Capture the running event loop at startup (in the FastAPI lifespan).
- In the MQTT `on_message` callback (thread), hand off via
  `loop.call_soon_threadsafe(...)` to push the reading into each subscriber queue.
- The SSE endpoint `await`s its queue and yields items.

### Work items (when we implement)
- [ ] `events.py`: thread-safe broadcaster — `publish(reading)` (from MQTT thread),
      `subscribe() -> asyncio.Queue` + cleanup on disconnect.
- [ ] Capture `asyncio.get_running_loop()` in the lifespan; expose to the MQTT client.
- [ ] Wire `mqtt_client._on_message` to call `broadcaster.publish(...)` after `store.update(...)`.
- [ ] `GET /telemetry/stream`: `text/event-stream` response yielding `data: <json>\n\n`
      per reading, with periodic keep-alive comments and clean disconnect handling.
- [ ] (Optional) `GET /` minimal HTML dashboard using `EventSource` to show live values.

### Dependencies
- SSE can be **hand-rolled with zero new deps** (generator yielding `text/event-stream`),
  OR add `sse-starlette` for keep-alives/disconnect niceties. Lean: hand-rolled first.
- WebSocket (if ever) needs `uvicorn[standard]` — already installed.

### Open sub-decisions
- Hand-rolled SSE vs `sse-starlette`?
- Stream every message, or throttle/coalesce to N Hz for slow/offscreen clients?
- Does the stream payload mirror `/telemetry/latest` JSON, or a slimmer per-field event?

## Open questions / decisions to make

RESOLVED (2026-10-06):
1. ~~Payload format~~ → **parse text server-side** (no firmware change).
2. ~~Language/framework~~ → **Python + FastAPI + paho-mqtt**.
5. ~~Final route names~~ → simple surface + SOVD surface (see README/routes above).
6. ~~Where it lives~~ → `~/repos/IEH/TRAceON-ThreadX/telemetry-server/`.

STILL OPEN:
3. **History:** latest-only for now; add a ring buffer + `GET /telemetry/history` if needed.
4. **Live updates:** polling works today; add SSE/WebSocket for a push dashboard later.
7. **OpenSOVD integration depth:** current SOVD surface is a minimal `data` resource shape.
   When OpenSOVD actually connects, decide: does it call these HTTP routes directly, or do we
   add a dedicated SOVD adapter module mapping to its gateway? Also consider `faults/` resources.

## Java / Eclipse-stack variant (SPIKE DONE — bonus-points option)

Context: using Eclipse-related **Java** may earn bonus technology points. Spiked a PoC in
`telemetry-server-java/` to validate the stack on this Mac.

- **Stack:** **Eclipse Jetty** (embedded HTTP, `jetty-ee10-servlet` 12.0.16) + **Eclipse Paho**
  (Java MQTT client, `org.eclipse.paho.client.mqttv3` 1.2.5). Both are Eclipse projects; with
  Eclipse Mosquitto as the broker, the whole path is Eclipse.
- **Build:** Maven (installed `maven` 3.10.0; JDK 27 present). `mvn package` → fat jar.
  Maven resolves deps from Maven Central fine (the managed-Mac/Docker proxy does NOT affect it).
- **Verified:** `java -jar ...` → Jetty serves `/health` + `/telemetry/latest`; Paho subscribes
  to `TRAceON/sensor-data`; published reading round-tripped (temperature_degC=25.5 etc.). ✅
- **Known rough edges in the spike (fix in a full version):**
  - SLF4J "no providers" warnings → add `slf4j-simple`.
  - JSON hand-rolled → 3-vectors render as strings; use **Jackson** for proper arrays.
  - Only `/health` + `/telemetry/latest` implemented (no SOVD routes / command yet).
- **Decision pending:** whether to port the full server (routes + SOVD surface + command) to
  Jetty/Paho for the bonus, keeping the Python FastAPI server as fallback. Spike confirms it's
  low-risk. NOTE: confirm which track actually awards the Java bonus (Innovation track's "extra
  technology points" vs Freestyle's PR-quality scoring).

## Not doing yet
Future enhancements remain in the backlog above (history, SSE, dashboard, auth, SQLite,
firmware JSON). MVP is implemented and verified; grow from here.
