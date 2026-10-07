# Known limitations & future work

We'd rather be upfront about what TRAceON does *not* do yet. None of these block
the demo; most are deliberate scope/hardening decisions for a hackathon. Each
entry notes the impact and a realistic path forward.

## Robustness

### 1. Log forwarding is a single blocking worker
Both servers forward logs with **one background worker** and a per-POST timeout
(~5 s). A slow or unreachable sink serializes at up to 5 s each, so the bounded
in-memory queue backs up and eventually drops entries (`dropped` counter rises).
- **Impact:** forwarding lags / drops under a slow sink; it never blocks MQTT
  ingestion or SSE (those are on separate paths), so the rest keeps working.
- **Path forward:** a small worker pool or async HTTP with a short connect
  timeout; make the timeout configurable.

### 2. SSE slow-client isolation is not unit-tested
The Java `SseRegistry` gives each subscriber its own bounded queue + writer
thread (drop-oldest) so one slow client can't block the MQTT publisher. This is
sound by construction and the server was verified running, but there is **no
automated test** asserting "a stalled client doesn't stall the others."
- **Impact:** a concurrency regression here could go unnoticed.
- **Path forward:** an integration test that opens two SSE clients, stalls one,
  and asserts the other keeps receiving.

### 3. Firmware MQTT client-ID is fixed
The board uses MQTT client id `TRAceON`; the Java server uses `traceon-java`.
Two instances sharing an id get kicked off the broker ("session taken over") —
we hit exactly this once with a duplicate server.
- **Impact:** running two boards, or two of the same server, collides.
- **Path forward:** derive a unique suffix (e.g. MAC-based on the board; a
  configurable id on the servers).

## Security

### 4. No authentication on the servers' control surface
`POST /command` (publishes to the board) and `POST /logs/forwarding/{start,stop}`
(the forward URL is caller-settable) are unauthenticated. The settable forward
URL is an SSRF-shaped risk if exposed to untrusted callers.
- **Impact:** anyone who can reach the server can command the board or point
  forwarding anywhere. Fine on a trusted/local network; not for public exposure.
- **Path forward:** an auth layer (token/mTLS) and/or an allow-list for the
  forward target; bind to localhost by default.

### 5. Board → broker is plaintext MQTT (no TLS)
The firmware publishes telemetry/logs over unencrypted MQTT on the LAN. A
WireGuard mesh (see below) would only cover computer-to-computer hops, not the
board's own Wi-Fi/MQTT leg (the AZ3166 can't run WireGuard).
- **Impact:** on-LAN traffic is observable/injectable; `cloud_config.h` also held
  plaintext Wi-Fi creds (now overridable at build time to keep them out of git).
- **Path forward:** MQTT-over-TLS (NetXDuo supports it) with the broker's CA
  baked in; broker auth instead of `allow_anonymous`.

## Integration / platform

### 6. Eclipse openDuT integration is a plan, not implemented
`OPENDUT-INTEGRATION.md` documents how to run the forwarding hop over an openDuT
WireGuard mesh and records a **proven blocker**: the openDuT `localenv` images
are amd64-only and run too slowly under emulation on Apple Silicon (Keycloak
times out CARL's init). EDGAR also cannot run on macOS at all.
- **Impact:** not demonstrated end-to-end; it's a design + findings doc.
- **Path forward:** two native x86_64 Linux hosts (the documented "happy path");
  only the forward URL changes to the overlay IP — no TRAceON code change.

### 7. openDuT / server deployment assumes a trusted single network
The servers find the broker by configured host (`TRACEON_MQTT_HOST`, default
`localhost`) — no discovery (mDNS/DNS-SD). Cross-machine setups require the
operator to supply the broker IP and ensure reachability (we hit AP
client-isolation / WSL-NAT / host-firewall issues at a venue).
- **Impact:** manual network config; fragile on hostile/guest networks.
- **Path forward:** service discovery, or the openDuT overlay (#6) to make the
  underlying network irrelevant.

## Testing scope

### 8. Firmware tests cover pure logic only
The firmware host tests (`app/common/tests/`) exercise hardware-free logic
(ISO-8601 formatting, JSON escaping, LogEntry assembly). They do **not** run the
RTOS, Wi-Fi/NetXDuo, SNTP, or real MQTT paths — those are validated on-target by
hand (and would need HIL, e.g. via openDuT).
- **Impact:** firmware concurrency/networking regressions aren't caught by CI.
- **Path forward:** on-target smoke tests / HIL in the openDuT cluster.

---

*None of the above affects the core demo path (board → broker → server →
dashboard, plus commands and log forwarding). They are the honest edges of a
hackathon-scoped project.*
