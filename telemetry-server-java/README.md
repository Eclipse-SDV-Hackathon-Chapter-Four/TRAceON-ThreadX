# TRAceON Telemetry Server — Java / Jakarta (Eclipse stack)

A second implementation of the telemetry HTTP server using the **Eclipse Jakarta
stack** (the Python FastAPI server in `../telemetry-server/` remains the primary /
fallback). Functionally equivalent: subscribes to the board's MQTT telemetry and
serves it over HTTP, with a SOVD-flavored surface for future OpenSOVD.

## Why this exists

Using Eclipse-related **Java** may earn hackathon bonus points. This server is built
entirely on Eclipse projects, three of them Jakarta EE 10 specs:

| Layer         | Jakarta spec                      | Eclipse implementation      |
|---------------|-----------------------------------|-----------------------------|
| HTTP / Servlet| Jakarta Servlet                   | Eclipse Jetty (`ee10`)      |
| REST routing  | Jakarta RESTful Web Services (JAX-RS) | Eclipse Jersey 3.1      |
| JSON binding  | Jakarta JSON Binding (JSON-B)     | Eclipse Yasson 3.0          |
| MQTT          | — (not Jakarta)                   | Eclipse Paho (Java)         |

Broker is Eclipse Mosquitto → the whole path is Eclipse.

## Prerequisites

- JDK 21+ (tested on JDK 27). Maven 3.x (`brew install maven`).
- The MQTT broker running (see `../BUILD_AND_RUN.md` — Docker `traceon-broker`).

## Build & run

```bash
cd ~/repos/IEH/TRAceON-ThreadX/telemetry-server-java
export JAVA_HOME="$(/usr/libexec/java_home 2>/dev/null || echo /opt/homebrew/opt/openjdk)"
mvn clean package
java -jar target/telemetry-telemetry-server-java-jar-with-dependencies.jar
```

Server listens on **:8082** (the Python server uses 8083, so both can run at once).
Override via env vars: `TRACEON_MQTT_HOST`, `TRACEON_MQTT_PORT`, `TRACEON_SENSOR_TOPIC`,
`TRACEON_COMMAND_TOPIC`, `TRACEON_COMPONENT`, `TRACEON_HTTP_PORT`.

## Run in Docker (so LAN peers can reach it)

Like the broker and the Python server, running in Docker publishes the port
through Docker's network layer, bypassing the managed Mac's application firewall.
Teammates on the same WiFi reach it at `http://<LAN-ip>:8082`.

```bash
cd ~/repos/IEH/TRAceON-ThreadX/telemetry-server-java

# Multi-stage build (Maven build happens inside the container).
docker build \
  --build-arg http_proxy= --build-arg https_proxy= \
  --build-arg HTTP_PROXY= --build-arg HTTPS_PROXY= \
  -t traceon-telemetry-server-java:latest .

docker run -d --name traceon-server-java -p 8082:8082 traceon-telemetry-server-java:latest

docker logs -f traceon-server-java
docker stop traceon-server-java
docker rm -f traceon-server-java
```

The container reaches the broker via `host.docker.internal:1883` (image default).

## Routes (identical shape to the Python server)

Streaming (Server-Sent Events) — the primary telemetry/logs interface:
- `GET  /telemetry/entries`  (live telemetry, SSE)
- `GET  /logs/entries`       (live logs, SSE). Each frame is an ISO **EventEnvelope**
  `{timestamp (server emit), payload: LogEntry, error}`. Board publishes ISO 17978-3
  `LogEntry` `{timestamp, context (AUTOSAR_DLT object), severity (DLT_*), msg}` on
  `TRAceON/logs`.

Log forwarding (optional sink, separate from SSE; `TRACEON_LOG_FORWARD_URL`):
- `GET  /logs/forwarding`        (status)
- `POST /logs/forwarding/start`  (optional `{"url":"..."}` override)
- `POST /logs/forwarding/stop`

> Full API: see [`../API.md`](../API.md).

Simple:
- `GET  /health`
- `GET  /telemetry/latest/{field}`  (one field snapshot)
- `POST /command`  (body `{"message":"..."}` → published to `TRAceON/incoming`)

History (ring buffers, last 100; `since`/`until` are ISO-8601 on server receive time):
- `GET  /telemetry/history`  — `limit`, `field`, `since`, `until`
- `GET  /logs/history`       — `limit`, `severity` (comma-sep), `context`, `since`, `until`
  (invalid `since`/`until` → HTTP 400)

SOVD-flavored:
- `GET  /components/TRAceON/data`
- `GET  /components/TRAceON/data/{resourceId}`

> The standalone snapshots `/telemetry/latest` and `/logs` were removed; the
> `entries` endpoints are SSE streams. Use the per-field route or SOVD `data`
> resources for one-shot reads.

```bash
curl -s localhost:8082/telemetry/latest/temperature_degC
curl -s localhost:8082/components/TRAceON/data/acceleration_mg
# {"id":"acceleration_mg","data":[0.1,0.2,981.5]}   <- JSON arrays via JSON-B
curl -s -X POST localhost:8082/command -H 'content-type: application/json' -d '{"message":"hi"}'
# Live streams (keep connection open):
curl -N localhost:8082/telemetry/entries
curl -N localhost:8082/logs/entries
```

Env vars: `TRACEON_MQTT_HOST/PORT`, `TRACEON_SENSOR_TOPIC`, `TRACEON_LOG_TOPIC`
(default `TRAceON/logs`), `TRACEON_LOG_BUFFER_SIZE` (default 100),
`TRACEON_TELEMETRY_HISTORY_SIZE` (default 100),
`TRACEON_COMMAND_TOPIC`, `TRACEON_COMPONENT`, `TRACEON_HTTP_PORT`.

## Structure

```
src/main/java/org/traceon/
  Config.java            env-var config
  TelemetryStore.java    thread-safe latest reading (typed: Double / double[])
  LogStore.java          thread-safe log ring buffer (LogEntry, lenient parse)
  MqttService.java       Eclipse Paho: subscribe sensor+log topics, publishCommand
  Json.java              JSON-B (Yasson) serializer for SSE payloads
  SseRegistry.java       thread-safe SSE writer registry (telemetry/logs channels)
  SseServlet.java        plain async Jakarta servlet for the two SSE streams
  TelemetryResource.java JAX-RS: /health, /telemetry/latest(/{field}), POST /command, /logs
  SovdResource.java      JAX-RS: /components/{component}/data(/{resourceId})
  TelemetrySpike.java    main: Jetty + Jersey + JSON-B + SSE servlets wiring
```

## Gotcha #2: SSE on embedded Jetty — don't use JAX-RS SSE here

JAX-RS SSE (`SseEventSink` + `@Produces(SERVER_SENT_EVENTS)`) fails on this embedded
setup with `UnsupportedOperationException: Asynchronous processing not supported on
Servlet 2.x container` — **even though** the servlet context reports version 6 and the
holder has `asyncSupported=true`. Jersey's programmatically-registered `ServletContainer`
doesn't detect the async capability.

Fix applied: the two SSE endpoints are **plain Jakarta async servlets** (`SseServlet` via
`req.startAsync()`), mapped at exact paths ahead of the Jersey `/*` catch-all. Jersey still
serves all the JSON routes. Robust and dependency-free.

## Gotcha worth knowing: JSON-B in a fat-jar

Jersey normally auto-discovers the JSON-B provider (`jersey-media-json-binding`), but
the Maven **assembly** `jar-with-dependencies` merges/clobbers `META-INF/services`
files, breaking auto-discovery → every JSON response fails with
`MessageBodyWriter not found for media type=application/json` (HTTP 500), and JSON
request bodies fail with 415.

Fix applied: **explicitly register the feature** instead of relying on discovery:
```java
config.register(org.glassfish.jersey.jsonb.JsonBindingFeature.class);
```
(Alternative: use the Shade plugin with a `ServicesResourceTransformer`.)

## Status

Verified end-to-end against the live broker: all routes return correct JSON (vectors as
arrays), and `POST /command` round-trips through Paho to `TRAceON/incoming`.
