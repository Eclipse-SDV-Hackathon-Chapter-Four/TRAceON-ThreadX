# Design note: a server-side Eclipse uProtocol gateway

**Status:** design / stretch (post-hackathon). Not implemented. No TRAceON code
changes required to adopt it — it's an additive, reversible adapter.

**Goal:** expose the AZ3166's telemetry/logs in the **Eclipse uProtocol**
namespace so uProtocol-native consumers (e.g. the Rust log sink) can consume
them, **without** putting a uProtocol/protobuf stack on the microcontroller.

## Why a gateway (and not uProtocol on the board)

The AZ3166 runs bare-metal C on Eclipse ThreadX. uProtocol is protobuf-first and
its language SDKs are Rust/C++/Java/Python — there is **no MCU-ready C `up-client`**.
Putting uProtocol on the board would mean hand-writing UUri/UMessage/UTransport
plus linking nanopb — real effort, for a benefit (transport independence,
vehicle-wide addressing) a single-board MQTT demo never exercises.

A **gateway on the server side** is the pragmatic split:

```
AZ3166 ──raw MQTT──► Mosquitto ──► ┌──────────────────────────────┐
 (unchanged)         TRAceON/*      │  uProtocol Gateway (host)     │
                                    │  ingress: raw-MQTT subscriber │
                                    │  map: topic+payload → UUri    │
                                    │       + UMessage              │
                                    │  egress: up-transport-mqtt5   │──► uProtocol
                                    └──────────────────────────────┘      consumers
```

It is an **anti-corruption layer**: raw MQTT on the ingress edge, uProtocol on
the egress edge. The board never learns uProtocol exists; uProtocol consumers
never learn the board speaks raw MQTT. All translation lives in one testable
place.

**Where it should live:** a new Rust crate alongside the team's existing Rust
diagnostic components (that's where `up-rust` / `up-transport-mqtt5` and the
uProtocol familiarity already are) — **not** in the firmware/server repo.

## UUri mapping (ingress: board → uProtocol)

Current raw topics (from `app/mqtt/cloud_config.h`) map to structured UUris.
Numbers below are **illustrative placeholders** — finalize against the uProtocol
spec. Convention: resource IDs ≥ `0x8000` denote publish topics; `0x0000` is the
RPC/notification sink of an entity.

| Raw MQTT topic        | Direction        | UUri (illustrative)        | uMessage type |
|-----------------------|------------------|----------------------------|---------------|
| `TRAceON/sensor-data` | board → consumers| `//traceon/A810/1/8001`    | PUBLISH       |
| `TRAceON/logs`        | board → consumers| `//traceon/A810/1/8002`    | PUBLISH       |
| `TRAceON/incoming`    | consumers → board| `//traceon/A810/1/0000`    | NOTIFICATION  |

- `//traceon` — authority (the vehicle/device authority name).
- `A810` — uEntity id (placeholder for "the TRAceON board app").
- `1` — uEntity major version.
- resource id — `8001` sensor_data, `8002` logs, `0000` command sink.

## Payload format decision

Two options; **start with pass-through, offer structured as a follow-up.**

1. **Pass-through (recommended first step).** Keep the board's existing payload
   bytes as the `UPayload`, format `UPAYLOAD_FORMAT_TEXT`
   (telemetry is a text block; logs are JSON). Minimal work; consumers parse the
   same bytes they do today, just unwrapped from a UMessage. Gets the uProtocol
   addressing + envelope benefits immediately.
2. **Structured (follow-up).** Parse the board's text into a typed protobuf
   message (reuse the ISO 17978 / SOVD `LogEntry` shape) and set
   `UPAYLOAD_FORMAT_PROTOBUF`. Now it's a fully typed uProtocol message, at the
   cost of a `.proto` + mapping code in the gateway.

Either way the UUID/ttl/priority metadata in `UAttributes` is populated by the
gateway, not the board.

## Message flow — both directions

**Telemetry/logs (ingress):** plain-MQTT subscribe to `TRAceON/#` →
topic→UUri lookup → build `UMessage` (PUBLISH) → `up_transport.send(msg)`.
Unmapped topics are logged and dropped (no guessing).

**Commands (egress → board):** a uProtocol consumer sends a NOTIFICATION to the
board's command UUri (`.../0000`). The gateway `register_listener`s on that UUri,
extracts the payload, and does a plain `publish("TRAceON/incoming", payload)` to
Mosquitto — preserving the existing `POST /command → TRAceON/incoming → OLED`
path, just fronted by uProtocol.

## Sketch (Rust, illustrative — not compile-ready)

```rust
use up_rust::{UTransport, UMessageBuilder, UUri, UPayloadFormat};
use up_transport_mqtt5::Mqtt5Transport;   // uProtocol egress
use rumqttc::{AsyncClient, Event, Packet, QoS}; // plain-MQTT ingress (the board)

let up_tx = Mqtt5Transport::new(/* uP broker/config */).await?;
let (raw, mut raw_events) = AsyncClient::new(/* Mosquitto :1883 */, 10);
raw.subscribe("TRAceON/#", QoS::AtLeastOnce).await?;

while let Ok(Event::Incoming(Packet::Publish(p))) = raw_events.poll().await {
    let source: UUri = match p.topic.as_str() {
        "TRAceON/sensor-data" => uuri("traceon", 0x8001),
        "TRAceON/logs"        => uuri("traceon", 0x8002),
        other => { log::warn!("unmapped topic {other}"); continue; }
    };
    let msg = UMessageBuilder::publish(source)
        .with_ttl(10_000)
        .build_with_payload(p.payload, UPayloadFormat::UPAYLOAD_FORMAT_TEXT)?;
    up_tx.send(msg).await?;
}

// Reverse: register a listener on //traceon/A810/1/0000 and, on each
// NOTIFICATION, raw.publish("TRAceON/incoming", payload).
```

## How it fits the current system

- **Firmware:** unchanged.
- **Two HTTP servers (Python/Java):** unchanged — they keep their raw-MQTT
  subscription for the dashboard/SSE/SOVD surface. They *could* later become
  uProtocol consumers, but nothing forces it.
- **Rust log sink (PR #4):** the natural first uProtocol consumer — register a
  listener on `//traceon/.../8002` instead of a raw subscription.
- **Rollout:** run the gateway alongside everything; if it misbehaves, nothing
  else depends on it.

## Honest trade-offs

- This is a **translation shim**, not "the board speaks uProtocol." Pitch it
  precisely: *the board publishes raw MQTT; a server-side uProtocol gateway
  adapts it into the uProtocol namespace for uProtocol-native consumers.*
- The **UUri numbering + payload format are design decisions** we define; this
  doc is where they're recorded. Finalize IDs against the uProtocol spec.
- **protobuf/UUID deps live in the gateway** (a host process) — fine there,
  unlike on the MCU.
- **Scope:** stretch / post-hackathon. It does not touch the demo-critical path
  (board → broker → server → dashboard, commands, forwarding).
