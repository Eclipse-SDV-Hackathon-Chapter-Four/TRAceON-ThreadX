# TRAceON — Architecture Overview

A telemetry-and-logging pipeline from an **MXChip AZ3166** IoT DevKit to a
Mac-side HTTP server, using **ISO 17978-3 (SOVD)** log/event models and built on
**Eclipse SDV / Eclipse Foundation** components throughout.

```
   ┌─────────────────────────┐        MQTT          ┌──────────────────┐        HTTP
   │   MXChip AZ3166 (board)  │  ───────────────►   │  MQTT broker     │   ◄── subscribe
   │   Azure RTOS / ThreadX   │   TRAceON/sensor-data│  Eclipse         │       │
   │   + NetXDuo TCP/IP+MQTT  │   TRAceON/logs       │  Mosquitto 2     │       ▼
   │                          │  ◄───────────────   │  (Docker)        │   ┌──────────────────────┐
   │   sensors → telemetry    │   TRAceON/incoming   │  :1883           │   │  Telemetry server    │
   │   logger  → ISO LogEntry │                      └──────────────────┘   │  (Python OR Java)    │
   └─────────────────────────┘                                             │                      │
                                                                            │  SSE streams         │──► browsers / curl
                                                                            │  History snapshots   │
                                                                            │  Log forwarding ─────┼──► downstream sink
                                                                            │  SOVD-shaped reads   │     (HTTP POST)
                                                                            └──────────────────────┘
```

---

## On the board (MXChip AZ3166)

Bare-metal firmware (C), flashed to the STM32F412-based DevKit. No OS beyond the
RTOS; everything is cooperative tasks on **ThreadX**.

| Concern | What runs | Eclipse component |
|---|---|---|
| RTOS / scheduling | Threads for telemetry, MQTT, app logic | **Eclipse ThreadX** (formerly Azure RTOS) |
| TCP/IP + MQTT client + TLS stack | WiFi → DHCP → SNTP → MQTT connect/publish | **Eclipse NetXDuo** (`nxd_mqtt_client`) |
| Base project | AZ3166 "Eclipse ThreadX" sample lineage | Eclipse ThreadX getting-started sample |

**Application firmware (our code, in `app/`):**
- `app/mqtt/telemetry.c` — reads the on-board sensors (temperature, humidity,
  pressure, accelerometer, magnetometer) and publishes readings to
  `TRAceON/sensor-data` each cycle. Also emits plausibility-check warnings and,
  in **DEMO_LOGS mode** (on by default; see `telemetry.h`), a varied stream of
  log entries (~1/sec, cycling all DLT severities and several contexts) to
  exercise the log path.
- `app/common/logger.{c,h}` — the **ISO 17978-3 logger**. `traceon_log(context,
  severity, msg)` (+ `_info/_warn/_error/...` wrappers) formats an ISO `LogEntry`
  JSON — ISO-8601 timestamp from SNTP, `context.type = "AUTOSAR_DLT"`, DLT
  severities (`DLT_INFO`, `DLT_WARN`, …) — and publishes it to `TRAceON/logs`.
- `app/mqtt/mqtt_client.{c,h}` — NetXDuo MQTT wrapper: connect, publish telemetry
  + logs, subscribe to `TRAceON/incoming` (commands shown on the OLED).
- `app/mqtt/cloud_config.h` — WiFi SSID/password, broker IP, client name
  (`TRAceON`) and topic names (compile-time constants).

**Topics (board ↔ broker):**
- `TRAceON/sensor-data` — telemetry (board → server)
- `TRAceON/logs` — ISO `LogEntry` logs (board → server)
- `TRAceON/incoming` — commands (server → board)

---

## The broker

- **Eclipse Mosquitto 2** (`eclipse-mosquitto:2`), run in **Docker**, publishes
  `:1883`. Running it in a container is deliberate: Docker's published ports
  bypass the managed Mac's inbound application firewall, so the board (and other
  LAN peers) can reach it where a native listener could not.

---

## On the server (Mac)

Two interchangeable implementations expose the **identical HTTP API**; both
subscribe to the broker and turn MQTT messages into HTTP-consumable telemetry and
logs. The log/event wire format follows **ISO 17978-3 (SOVD)**: `LogEntry`
(Table 316) wrapped in an `EventEnvelope` (Table 5) on the live streams.

### Python implementation (`telemetry-server/`, port 8083)
| Concern | Library |
|---|---|
| HTTP + SSE + auto docs | **FastAPI** / **Uvicorn** (ASGI) |
| MQTT subscriber | **Eclipse Paho** (`paho-mqtt`) |
| Models / validation | Pydantic |

### Java implementation (`telemetry-server-java/`, port 8082)
| Concern | Library |
|---|---|
| Embedded HTTP server | **Eclipse Jetty** (`jetty-ee10-servlet`) |
| JAX-RS (REST + SSE) | Jersey |
| JSON binding (JSON-B) | **Eclipse Yasson** |
| MQTT subscriber | **Eclipse Paho** (`org.eclipse.paho.client.mqttv3`) |

> Both server images set `NO_PROXY=*` so that, when containerized, outbound log
> forwarding bypasses the Docker-injected (unreachable) corporate proxy.

### What the server exposes
- **Live streams (default interface):** `GET /telemetry/entries`,
  `GET /logs/entries` — Server-Sent Events; log events are wrapped in the ISO
  `EventEnvelope`.
- **History snapshots:** `GET /telemetry/history`, `GET /logs/history` — last-N
  with filters (severity, context, time). `/logs/history` returns a plain
  ISO-pure `LogEntry` list.
- **Log forwarding (optional, separate from SSE):** POSTs each received
  `LogEntry` to a downstream sink URL (`TRACEON_LOG_FORWARD_URL`), controlled at
  runtime via `GET|POST /logs/forwarding[/start|/stop]`. Off by default.
- **SOVD-flavoured reads:** `GET /components/{component}/data[/{resource}]`
  (ISO-shaped data resources; component id = `TRAceON`).
- **Command to board:** `POST /command` → published to `TRAceON/incoming`.
- **Live dashboard (Java server only):** `GET /dashboard` — a self-contained HTML
  page that consumes the SSE streams (same-origin) and shows live telemetry +
  color-coded ISO logs.

Full reference: `API.md`.

---

## ISO 17978-3 (SOVD) conformance

The project adopts the SOVD **HTTP contract** (not the reference Rust crate):

- **`LogEntry` (Table 316):** `{ timestamp, context, severity, msg }`, with
  `context.type = "AUTOSAR_DLT"` carrying `application_id` / `context_id`, and
  DLT severities. Emitted at the source — on the **board**.
- **`EventEnvelope` (Table 5):** `{ timestamp (server emit), payload, error }`,
  used to wrap each event on the SSE log stream.

The board speaks ISO natively; the server validates/normalises, streams,
snapshots, and (optionally) forwards it.

---

## End-to-end data flow

1. Board reads sensors → publishes telemetry to `TRAceON/sensor-data` and ISO
   `LogEntry` logs to `TRAceON/logs` (ThreadX task + NetXDuo MQTT client).
2. Mosquitto relays to the subscribed server (Paho client).
3. Server updates in-memory ring buffers and broadcasts over SSE
   (`EventEnvelope`-wrapped), serves history snapshots, and — if forwarding is
   on — POSTs each `LogEntry` to a downstream sink.
4. Commands flow back: `POST /command` → `TRAceON/incoming` → board OLED.

## Eclipse components at a glance

- **Eclipse ThreadX** — RTOS on the board
- **Eclipse NetXDuo** — TCP/IP stack + MQTT client (+ TLS) on the board
- **Eclipse Mosquitto** — MQTT broker
- **Eclipse Paho** — MQTT client on both servers
- **Eclipse Jetty** + **Eclipse Yasson** — HTTP server + JSON-B in the Java server
