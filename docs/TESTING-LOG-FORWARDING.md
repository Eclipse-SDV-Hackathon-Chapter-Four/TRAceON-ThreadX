# Testing Log Forwarding (POST sink)

How to test the TRAceON server's **log forwarding** feature — where the server
POSTs each received `LogEntry` to a downstream URL. Two scenarios:

1. **Local test** — server + sink on this Mac.
2. **Over WiFi** — another device on the network receives the forwarded logs.

Forwarding is **separate from SSE**, **OFF by default**, and controlled at runtime
via `POST /logs/forwarding/start|stop`. The target URL comes from the
`TRACEON_LOG_FORWARD_URL` env var (or a `{"url": "..."}` body on `start`).

> Paths below assume the project at `~/repos/IEH/TRAceON-ThreadX`.
> The MQTT broker (Docker `traceon-broker`) and a log source (the board, or a
> manual publish) must be running. Check: `docker ps` shows `traceon-broker`.

---

## Important: run the SERVER NATIVELY for forwarding tests

The containerized server cannot reliably POST to a sink in this Docker setup
(container egress times out — a local environment quirk). **For forwarding tests,
run the Python server natively** (`./run.sh`). The container is still the right
choice for *serving the API to the LAN*, just not for *outbound* POST testing.

---

## Scenario 1 — Local POST test (server + sink on this Mac)

### Terminal 1 — a throwaway sink that prints what it receives
```bash
python3 -c "
from http.server import BaseHTTPRequestHandler, HTTPServer
class H(BaseHTTPRequestHandler):
    def log_message(self,*a): pass
    def do_POST(self):
        n=int(self.headers.get('content-length',0))
        print('GOT', self.rfile.read(n).decode('utf-8','replace'), flush=True)
        self.send_response(200); self.end_headers(); self.wfile.write(b'ok')
print('sink on http://127.0.0.1:8080', flush=True)
HTTPServer(('127.0.0.1',8080),H).serve_forever()"
```

### Terminal 2 — run the server natively, pointed at the broker + the sink
```bash
cd ~/repos/IEH/TRAceON-ThreadX/telemetry-server
# One-time: create the venv if it doesn't exist
[ -d .venv ] || ./setup.sh

TRACEON_MQTT_HOST=localhost \
TRACEON_LOG_FORWARD_URL=http://127.0.0.1:8080/logs \
./run.sh
```
(Only one process can own port 8083 — stop the Docker server first if it's running:
`docker stop traceon-server`.)

### Terminal 3 — start forwarding, then make logs flow
```bash
cd ~/repos/IEH/TRAceON-ThreadX

# Turn forwarding ON
curl -s -X POST localhost:8083/logs/forwarding/start ; echo

# Make a log appear — either:
#  (a) send one manually (NOTE: run from the project dir, or use the full path):
./scripts/send-log.sh DLT_WARN SensorTask "hello from local test"
#  (b) or just wait for the board's demo log stream (~1/sec) if it is connected.

# Watch the counter rise (forwarded increments per log):
curl -s localhost:8083/logs/forwarding ; echo

# Turn forwarding OFF
curl -s -X POST localhost:8083/logs/forwarding/stop ; echo
```

**Expected:** Terminal 1 prints `GOT {"timestamp": ..., "severity": "DLT_WARN", ...}`
for each log, and `/logs/forwarding` shows `forwarded` rising with `failed: 0`.

---

## Scenario 2 — Forward to another client over WiFi

Here the sink runs on a **second device** on the same WiFi, and our server POSTs
to it across the network.

### On the receiving device (the other client)
Run an HTTP endpoint that accepts POSTs on a chosen port (e.g. 8080). Any of:

- **Python (same snippet, bind all interfaces):**
  ```bash
  python3 -c "
  from http.server import BaseHTTPRequestHandler, HTTPServer
  class H(BaseHTTPRequestHandler):
      def do_POST(self):
          n=int(self.headers.get('content-length',0))
          print('GOT', self.rfile.read(n).decode(), flush=True)
          self.send_response(200); self.end_headers()
  HTTPServer(('0.0.0.0',8080),H).serve_forever()"
  ```
  Note the **`0.0.0.0`** bind (not `127.0.0.1`) so it accepts remote connections.

- Find that device's LAN IP (e.g. on macOS `ipconfig getifaddr en0`,
  on Linux `hostname -I`). In the examples below it is `192.168.88.252` —
  replace it with your receiving device's actual IP.
- Make sure the device's **firewall allows inbound** on that port.

### On this Mac (the server)
Point forwarding at the other device. Easiest is to pass the URL on `start`
(no restart needed):
```bash
curl -s -X POST localhost:8083/logs/forwarding/start \
     -H 'content-type: application/json' \
     -d '{"url":"http://192.168.88.252:8080/logs"}' ; echo
```
Or bake it into the server's environment at launch:
```bash
cd ~/repos/IEH/TRAceON-ThreadX/telemetry-server
TRACEON_MQTT_HOST=localhost \
TRACEON_LOG_FORWARD_URL=http://192.168.88.252:8080/logs \
./run.sh
```

Then make logs flow (the board's demo log stream, or `./scripts/send-log.sh ...`) and watch the
other device print the forwarded entries. Confirm on the server side:
```bash
curl -s localhost:8083/logs/forwarding ; echo   # forwarded rising, failed 0
```

**If `failed` rises instead of `forwarded`:** the server can't reach the client.
Check, in order: the client's sink is bound to `0.0.0.0` (not localhost); the
client's firewall allows the port; both devices are on the same subnet and the
WiFi AP does not isolate clients; the IP (`192.168.88.252`) is current.

---

## Pre-flight: ALWAYS test the TCP path before enabling forwarding

Before `POST /logs/forwarding/start` for a remote sink, run **two** checks.

### 1. Route check — make sure the sink isn't captured by a VPN

A split-tunnel VPN (e.g. the corporate `utun4` on this managed Mac) installs
routes for `10.0.0.0/8`, `11.0.0.0/8`, and `172.16.0.0/12`. If the sink's IP
falls in one of those ranges and no **more-specific** LAN route covers it, the
outbound POST is pulled into the VPN tunnel and never reaches the sink.

```bash
route -n get <sink-ip> | grep interface   # e.g. route -n get 192.168.88.252
```

- **`interface: en0`** (your Wi-Fi) → good, traffic goes out the LAN.
- **`interface: utunN`** → the VPN is capturing it; forwarding will fail. Use a
  sink on a `192.168.x` address (the VPN here does not route `192.168/16`), or
  disconnect the VPN.

(Tailscale was ruled out as a cause — when enabled it advertised no routes and
used no exit node, so it did not capture LAN/`172.30`/`192.168` traffic. The VPN
to watch is `utun4`.)

### 2. TCP connect check — confirm the port is actually reachable

Confirm this machine can open a TCP connection to the sink's port. **Do not rely
on `ping`** — many hosts (Windows, macOS stealth mode, Linux desktops) block ICMP
while still accepting TCP, so ping can fail even when forwarding would work.

```bash
nc -vz -w 3 <sink-host> 8080     # e.g. nc -vz -w 3 192.168.88.252 8080
```

- **`succeeded!`** → the path is open; `forwarding/start` will deliver. Proceed.
- **`Connection refused`** → host is up but nothing is listening on 8080. Start the
  sink there and bind it to `0.0.0.0` (not `127.0.0.1`).
- **`timed out`** → a firewall is dropping the port, or the WiFi AP isolates
  clients. See the troubleshooting notes below.

Only turn on forwarding once the route is `en0` **and** `nc -vz` reports
`succeeded!`.

---

## Control endpoints (reference)

| Method | Path | Purpose |
|---|---|---|
| GET  | `/logs/forwarding` | Status: `{enabled, url, forwarded, failed, dropped, queued}` |
| POST | `/logs/forwarding/start` | Start. Optional body `{"url":"..."}` overrides the env URL. 400 if no URL set. |
| POST | `/logs/forwarding/stop` | Stop. |

Forwarded body = the ISO `LogEntry`:
```json
{"timestamp":"...","context":{"type":"AUTOSAR_DLT","context_id":"...",...},
 "severity":"DLT_WARN","msg":"..."}
```

## Troubleshooting

- **`forwarded` and `failed` both stay 0** → no logs are arriving. The board may be
  disconnected (check `docker logs traceon-broker | grep 'as TRAceON'`); press the
  board RESET, or inject with `./scripts/send-log.sh`.
- **`send-log.sh: no such file or directory`** → run it from the project root
  (`cd ~/repos/IEH/TRAceON-ThreadX`) or use the full path.
- **`start` returns 400** → no URL configured; pass `{"url":"..."}` in the body or
  set `TRACEON_LOG_FORWARD_URL` before launching the server.
- **Board stopped sending** → its MQTT connection dropped (`Publish failed with
  code: 65538` = NOT_CONNECTED on the serial console). The firmware now
  **auto-reconnects** (bounded backoff) once the broker is reachable again, so
  this should recover on its own; a RESET only speeds it up.
- **`failed` rises with `<urlopen error timed out>`** → the sink host accepted no
  response. `ping` is NOT a valid test here (ICMP is often blocked); use
  `nc -vz <sink-host> 8080`. If `nc` also times out *even with the sink's firewall
  fully off*, the WiFi AP is isolating clients — switch networks (a phone hotspot
  that permits peer traffic, or wired). Note: some phone hotspots ALSO isolate
  clients, so test with `nc -vz` after joining.
- **`<urlopen error ... Connection refused>`** (fast, not a timeout) → the host is
  reachable but nothing is listening on the port, or the sink is bound to
  `127.0.0.1` instead of `0.0.0.0`.
- **Container forwarding hangs/times out but native works** → Docker Desktop
  injects an unreachable HTTP proxy into containers (`~/.docker/config.json`
  `proxies.default`). The server images set `NO_PROXY=*` to bypass it; if you run
  a different container, add `-e NO_PROXY='*'`.
- **Can reach a server on `localhost` but not via the Mac's LAN IP** → on this
  managed Mac, **port 8081 is intercepted** by a local agent (TCP connects but
  HTTP resets). Use a verified-clean port (8080/8082/8083/8090/9090/7070/8000 all
  tested clean). The sink uses 8080; the Python server moved to 8083 for this
  reason.
