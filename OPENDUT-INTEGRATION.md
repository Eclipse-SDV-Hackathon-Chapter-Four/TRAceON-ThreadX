# Plan: Testing TRAceON components with Eclipse openDuT

**Status:** proposal / next-steps (not yet implemented).
**Goal:** use Eclipse **openDuT** to give TRAceON a reliable, repeatable, network-
agnostic test setup — primarily to solve the cross-machine reachability problems
we hit at the venue (AP client isolation, Windows/Hyper-V firewall, WSL NAT) that
blocked the Mac → Windows log-forward hop.

All CLEO commands below were taken from the openDuT user manual
(<https://opendut.eclipse.dev/book/>) and the Ethernet usage example. Flags are
accurate to that reference; still run `opendut-cleo <subcommand> --help` on your
installed version to confirm.

---

## What openDuT is (and the honest fit for us)

openDuT builds an **end-to-end-encrypted private network** between devices under
test using **EDGAR** edge agents. Components:

- **CARL** — central backend (manages peers/clusters), serves the **LEA** web UI.
- **EDGAR** — a **Linux** edge agent installed next to each device/host; it is the
  *peer* in the network. Joins a NetBird/**WireGuard** mesh.
- **CLEO** — CLI against CARL (create/list/describe/delete peers, devices,
  clusters, container-executors; generate setup-strings).
- **LEA** — web UI equivalent of CLEO.

**How the mesh works (important detail from the docs):** EDGAR tunnels
**Layer-2 Ethernet** traffic between peers using **GRE encapsulated in WireGuard**
(star topology, relayed via CARL if no direct path). It bridges a peer's
*physical interface* (`br-opendut`) into the cluster so devices appear on the same
LAN regardless of physical location.

### Honest caveats for TRAceON

1. **EDGAR is Linux-only and host-side.** The **AZ3166 cannot run EDGAR** (bare-
   metal ThreadX). The board is **not** a mesh peer.
2. **openDuT's device model assumes a wired interface** bridged into `br-opendut`.
   Our board talks **Wi-Fi/MQTT to the Mac broker**, not wired Ethernet into an
   EDGAR host — so the board does not map onto an openDuT "device (DuT)" cleanly.
   The realistic use is **host-to-host connectivity between the two computers**,
   not representing the board as a DuT.
3. **The value for us is the computer↔computer transport**: run EDGAR on the Mac
   and on a **Linux host / WSL** on the Windows side, put them in one cluster, and
   forward logs over the overlay — bypassing the venue network entirely.
4. **The board → broker hop still rides plain Wi-Fi.** openDuT does not help that
   leg; it only rescues the Mac → sink forward hop.
5. **Deployment is non-trivial:** CARL brings up Keycloak + NetBird + telemetry via
   Docker Compose. This is a real deployment, best treated as post-hackathon work.

---

## Target topology

```
   AZ3166 (ThreadX)  ── plain Wi-Fi / MQTT (NOT in the mesh) ──┐
                                                               ▼
┌───────────────────────────┐   GRE-over-WireGuard   ┌───────────────────────────┐
│ Mac  — EDGAR peer "mac"    │◀══════ overlay ═══════▶│ WSL/Linux — EDGAR "sink"  │
│  • Mosquitto broker (1883) │   (NetBird mesh)       │  • Rust log sink :8080    │
│  • telemetry server :8082  │                        │                           │
│    forwards ───────────────┼──▶ http://<overlay-sink>:8080/internal/logs        │
└───────────────────────────┘                        └───────────────────────────┘
            ▲ CARL (backend + Keycloak + NetBird + LEA) coordinates the cluster
```

---

## Plan

### Phase 0 — Prerequisites
- A Linux environment for EDGAR on **both** sides. Mac: EDGAR has no native macOS
  build → run it in a **Linux VM / container** on the Mac (or host the broker+server
  on a small Linux box). Windows side: **WSL2** (verify WireGuard/GRE kernel
  modules load; `ip_gre`, `gre`, WireGuard) or a dedicated Linux host.
- Docker + Docker Compose for the CARL stack.
- Decide where CARL runs (a cloud VM both peers can reach is simplest, since CARL
  can relay WireGuard when no direct path exists).

### Phase 1 — Deploy CARL (backend)
From the openDuT repo, local deployment is automated via Docker Compose:
```bash
git clone https://github.com/eclipse-opendut/opendut.git
cd opendut
export OPENDUT_REPO_ROOT=$(git rev-parse --show-toplevel)
# provision secrets
docker compose --file .ci/deploy/localenv/docker-compose.yml \
  --env-file .ci/deploy/localenv/.env.development up --build provision-secrets
docker cp opendut-provision-secrets:/provision/ .ci/deploy/localenv/data/secrets/
# bring up the stack (CARL + Keycloak + NetBird + LEA + telemetry)
docker compose --file .ci/deploy/localenv/docker-compose.yml \
  --env-file .ci/deploy/localenv/.env.development \
  --env-file .ci/deploy/localenv/data/secrets/.env up --detach --build
```
- LEA UI + CARL become reachable (default domains like `opendut.local`,
  `auth.opendut.local`; add `/etc/hosts` entries if no DNS).
- Secrets (incl. the CLEO OIDC client secret) land in
  `.ci/deploy/localenv/data/secrets/.env`.

### Phase 2 — Configure CLEO to talk to CARL
```bash
export OPENDUT_CLEO_NETWORK_CARL_HOST=opendut.local
export OPENDUT_CLEO_NETWORK_CARL_PORT=443
export OPENDUT_CLEO_NETWORK_OIDC_CLIENT_ID=opendut-cleo-client
export OPENDUT_CLEO_NETWORK_OIDC_CLIENT_SECRET=<from secrets/.env>
# SSL_CERT_FILE must point at the openDuT CA (secrets/pki/opendut-ca.pem)
opendut-cleo list peers      # smoke test
```

### Phase 3 — Create the two peers (one per computer)
```bash
# Mac side (broker + server)
opendut-cleo create peer --name mac-broker-server --location venue
# Windows/WSL side (sink)
opendut-cleo create peer --name wsl-sink --location venue

opendut-cleo list peers      # note the two PeerIDs
```

### Phase 4 — Add a network interface to each peer
openDuT requires an interface to bridge. Use the Ethernet form (even if logical);
replace the interface name with the real one on each host.
```bash
opendut-cleo create network-interface --peer-id <MAC_PEER_ID> --type eth --name eth0
opendut-cleo create network-interface --peer-id <WSL_PEER_ID> --type eth --name eth0
```
> Note: our forwarding only needs host↔host IP reachability over the overlay, not
> a bridged DuT. The interface is required by the model; the overlay IPs are what
> we actually use.

### Phase 5 — Generate setup-strings and run EDGAR on each host
```bash
opendut-cleo generate-setup-string --id <MAC_PEER_ID>   # copy the string
opendut-cleo generate-setup-string --id <WSL_PEER_ID>
```
On each host, install EDGAR (download from LEA → Downloads) and run the managed
scripted setup, pasting that host's setup-string:
```bash
./opendut-edgar setup managed       # prompts for the Setup-String
# if no CAN hardware (our case), you may need: ./opendut-edgar setup managed --skip-can
```
EDGAR joins the WireGuard mesh. Verify the tunnel interface exists:
```bash
ip link        # expect wt0 (WireGuard) and br-opendut
sudo wg        # shows the WireGuard peer(s)
```

### Phase 6 — Create and deploy the cluster
```bash
# descriptor groups the two peers; one must be leader
opendut-cleo create cluster-configuration \
    --name traceon-demo \
    --leader-id <MAC_PEER_ID> \
    --peer-ids <MAC_PEER_ID>,<WSL_PEER_ID>      # confirm exact flag via --help

opendut-cleo list cluster-configurations        # note the ClusterID
opendut-cleo create cluster-deployment --id <CLUSTER_ID>
```
Deploying establishes the GRE-over-WireGuard links between the peers.

> Alternatively, declare peers + cluster in one YAML and `opendut-cleo apply
> <file>` (kubectl-style). The manual documents the `PeerDescriptor` /
> `ClusterDescriptor` YAML schema.

### Phase 7 — Find the overlay IPs and pre-flight
On each host, read the overlay address assigned on `br-opendut` / `wt0`:
```bash
ip address show br-opendut      # or: ip address show wt0
```
Call the WSL sink's overlay IP `OVERLAY_SINK`. Reuse our pre-flight discipline
(from TESTING-LOG-FORWARDING.md) over the overlay before enabling forwarding:
```bash
# from the Mac peer:
nc -vz -w 3 OVERLAY_SINK 8080      # must say "succeeded!"
```

### Phase 8 — Point forwarding at the overlay IP
**Only runtime config changes — no firmware/server code change.**
```bash
# on the Mac, start the Java/Python server as usual, then:
curl -s -X POST localhost:8082/logs/forwarding/start \
     -H 'content-type: application/json' \
     -d '{"url":"http://OVERLAY_SINK:8080/internal/logs"}'
```
or bake it in at launch: `TRACEON_LOG_FORWARD_URL=http://OVERLAY_SINK:8080/internal/logs`.

Data path: board → Mac broker (Wi-Fi) → server → **POST over the openDuT overlay**
→ Rust sink. The venue AP / Windows firewall / WSL NAT are all bypassed.

---

## Optional: automated test via openDuT (VIPER / container-executor)

openDuT runs **containerized test applications** on a peer and uploads results to
a WebDAV dir (`/results/` + a `.results_ready` marker). We could codify a
**forward-and-verify** test:
```bash
opendut-cleo create container-executor \
    --peer-id <WSL_PEER_ID> \
    --engine docker \
    --name traceon-forward-check \
    --image <our-test-image> \
    --results-url http://nginx-webdav.opendut.local/
```
The test container would: run the sink, trigger log emission (board or
`send-log.sh`), assert the sink received the expected ISO LogEntries with the
right DLT severities, then write results. Deploying the cluster triggers it.

---

## What changes vs. stays the same

| Piece | Change for openDuT? |
|---|---|
| AZ3166 firmware | **None** — plain Wi-Fi/MQTT to the Mac broker |
| Mosquitto broker | None |
| Telemetry server (Py/Java) code | **None** |
| Rust sink code | None |
| **Forward URL** | `192.168.x` → **`OVERLAY_SINK`** (only functional change) |
| New infra | CARL+Keycloak+NetBird; EDGAR on both hosts (Linux/WSL) |

## Open risks to validate early
- **EDGAR on the Mac**: no native macOS build → needs a Linux VM/container, or
  move broker+server onto a Linux host.
- **EDGAR in WSL2**: privileged networking (WireGuard, GRE, `br-opendut`) may be
  restricted; validate `ip_gre`/`gre`/WireGuard modules load before committing.
- **Exact CLEO flag names** (`--peer-ids` / `--leader-id` / cluster subcommand
  spelling) — confirm with `--help` on the installed version.
- **CARL reachability**: both EDGARs must reach CARL; a cloud-hosted CARL avoids
  the same NAT problems we're trying to escape.
