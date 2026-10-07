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


---

## Local attempt findings (2026-10-07, macOS arm64, Mac-only)

We attempted a Mac-only bring-up of the **CARL backend** via the `localenv`
Docker Compose stack (Windows/WSL not in play yet, so EDGAR/mesh was explicitly
out of scope — the goal was just to stand up and explore the control plane).

**What worked:**
- Cloned `eclipse-opendut/opendut`; cargo 1.97 + Docker 29 + Compose v5 present.
- `provision-secrets` and the full image build succeeded **once the injected
  Docker proxy was neutralized** — same issue as our own images. Pass empty
  proxy build-args/env:
  `--build-arg http_proxy= --build-arg https_proxy= ... NO_PROXY='*'`.
  Without this, `apt-get` in the builds fails with "Unable to locate package".
- Added the nine `*.opendut.local` entries to `/etc/hosts` → `127.0.0.1`.
- Core services came up and reported healthy: **traefik, keycloak,
  keycloak-postgres, netbird-signal, netbird-relay**; `keycloak-init` completed
  ("Keycloak provisioned", realm/clients/roles created). Traefik correctly
  routed `auth.opendut.local` → the keycloak container.

**Fixes we had to apply for Docker Desktop on Mac:**
1. **Proxy** — empty proxy build-args (above).
2. **Lean subset** — `OPENDUT_LOCALENV_TELEMETRY_ENABLED=0` scales the Grafana/
   Prometheus/Loki/Tempo/Alloy/otel services to 0, BUT `grafana` still
   `depends_on: loki`, so `up` fails with "grafana is missing dependency loki".
   Workaround: start the core services **explicitly by name** with `--no-deps`
   (keycloak-postgres, keycloak, init_keycloak, traefik, netbird-signal,
   netbird-management, netbird-relay, carl, nginx-webdav).
3. **Cert bind mount** — the compose binds host path `/provision`
   (`SHARED_CERTS_HOST_DIR=/provision`) which doesn't exist / isn't shared on
   Docker Desktop for Mac → "mounts denied: path /provision is not shared".
   Workaround: override `SHARED_CERTS_HOST_DIR` to the real host PKI dir
   (`.ci/deploy/localenv/data/secrets/pki`, which is under the repo and already
   in Docker's shared file space).

**The blocker (why we stopped):**
- The localenv images (CARL, Keycloak, …) are **`linux/amd64` only**; the host
  is **arm64**, so they run under **QEMU emulation** (Docker logged the
  platform-mismatch warning for `opendut-carl`).
- Under emulation, **Keycloak responds far too slowly** — traefik logged ~3 s
  per request with `DownstreamStatus 499` (client gave up). CARL's init script
  loops on **"Waiting for https://auth.opendut.local/ to be available…"** and
  never gets past it, because its probe times out (~6 s) against the slow
  emulated Keycloak. CARL therefore never reaches `healthy`.
- This is an **architecture/emulation performance wall**, not a config bug.
  Raising CARL's init timeout might eventually let it settle, but the stack is
  likely to stay slow/flaky under emulation.

**Conclusion / recommendation:**
- openDuT's localenv is **not practically runnable on this Apple-Silicon Mac**
  with the published amd64 images. Defer the real bring-up to an **x86_64 Linux
  host** (or the Windows box via a Linux VM / WSL) where the images run natively
  and EDGAR's privileged networking (WireGuard/GRE/bridges) is also available —
  recall **EDGAR cannot run on macOS at all**, so the Mac was only ever viable
  for the control plane, not the mesh.
- Net: the three Docker-Desktop-on-Mac fixes above are reusable, but the whole
  effort should move to x86_64 Linux. The TRAceON side needs no code changes
  regardless — only the forward URL points at the overlay IP (see table above).

**Teardown:** `docker compose … down --remove-orphans` removes everything; our
`traceon-broker` is on a separate stack and is unaffected.


---

## Appendix: Linux host + WSL-on-Windows topology

A cleaner target than the Mac-only attempt above: a **native x86_64 Linux
machine** as one peer and **WSL2 on the Windows machine** as the other. This is
the recommended way to get a *working* mesh, because it removes the two walls we
hit on the Mac:

- **No emulation:** on x86_64 Linux the openDuT amd64 images (CARL, Keycloak, …)
  run **natively** — the Keycloak-too-slow / CARL-init-timeout blocker goes away.
- **Native EDGAR:** EDGAR runs natively on Linux (no VM), with real access to
  WireGuard / GRE / `br-opendut`.

### Roles

```
   AZ3166 (ThreadX) ── plain Wi-Fi / MQTT (NOT meshed) ──┐
                                                         ▼
┌──────────────────────────────┐  GRE/WireGuard  ┌──────────────────────────────┐
│ Linux box (x86_64)            │◀═══ overlay ═══▶│ Windows: WSL2 (Ubuntu)        │
│  • CARL + Keycloak + NetBird  │                 │  • EDGAR peer "wsl-sink"      │
│    (localenv, native amd64)   │                 │  • Rust log sink :8080        │
│  • Mosquitto broker           │                 │                               │
│  • telemetry server (fwd) ────┼──▶ http://<overlay-wsl>:8080/internal/logs      │
│  • EDGAR peer "linux-host"    │                 └──────────────────────────────┘
└──────────────────────────────┘
```

Who hosts what:
- The **Linux box** runs the CARL backend (`localenv`), the broker, the telemetry
  server, **and** its own EDGAR peer. Simplest: one machine owns the backend +
  one peer.
- **WSL2** runs the second EDGAR peer and the Rust sink.
- The **AZ3166** stays on plain Wi-Fi → the broker (unchanged; never meshed).

### Linux box — steps

1. **CARL backend** — same `localenv` Docker Compose as the main plan, but on
   x86_64 the images run natively (no emulation). Apply only the proxy fix if
   you're behind the same corporate proxy; the `/provision` mount and
   `grafana→loki` issues were **Docker-Desktop-on-Mac** artifacts and should not
   occur on native Linux Docker. Add `*.opendut.local` to `/etc/hosts` (or real
   DNS).
2. **CLEO** — configure against CARL (`OPENDUT_CLEO_NETWORK_CARL_HOST` + OIDC
   client secret from `secrets/.env`), then create peers:
   ```bash
   opendut-cleo create peer --name linux-host --location lab
   opendut-cleo create peer --name wsl-sink   --location lab
   opendut-cleo create network-interface --peer-id <LINUX_ID> --type eth --name eth0
   opendut-cleo create network-interface --peer-id <WSL_ID>   --type eth --name eth0
   opendut-cleo generate-setup-string --id <LINUX_ID>
   opendut-cleo generate-setup-string --id <WSL_ID>
   ```
3. **EDGAR (native)** — download EDGAR from LEA, then:
   ```bash
   sudo ./opendut-edgar setup managed --skip-can   # paste the linux-host setup-string
   ip link        # verify wt0 (WireGuard) + br-opendut appear
   ```

### WSL2 (Windows) — steps + the real caveats

WSL2 runs a full Linux kernel, so EDGAR *can* run there — **but the stock
Microsoft WSL2 kernel is missing pieces that WireGuard/NetBird and GRE need**
(certain nftables/iptables features and `ip_gre` are often not compiled in).
Expect to validate — and possibly rebuild — the kernel. Validate in this order:

1. **systemd in WSL** — EDGAR installs as a systemd service. Enable it:
   `/etc/wsl.conf` →
   ```ini
   [boot]
   systemd=true
   ```
   then `wsl --shutdown` from Windows and reopen. Confirm `systemctl` works.
2. **Kernel modules** — check WireGuard + GRE are available:
   ```bash
   modprobe wireguard && echo "wireguard ok"
   modprobe ip_gre && echo "ip_gre ok"
   zcat /proc/config.gz | grep -iE 'WIREGUARD|NF_TABLES|IP_GRE'   # if config present
   ```
   If these fail, you need a **custom WSL2 kernel** with WireGuard/GRE/netfilter
   enabled (well-trodden but non-trivial — several community kernels exist),
   configured via `.wslconfig` → `[wsl2] kernel=<path-to-bzImage>`.
3. **Mirrored networking (recommended)** — so the WSL peer is reachable on the
   Windows host's network without NAT gymnastics. In `%UserProfile%\.wslconfig`:
   ```ini
   [wsl2]
   networkingMode=mirrored
   ```
   then `wsl --shutdown`.
4. **EDGAR** — run as root (the container/service model needs it):
   ```bash
   sudo ./opendut-edgar setup managed --skip-can   # paste the wsl-sink setup-string
   ```
5. **Rust sink** — run it in WSL on `0.0.0.0:8080` (now reachable via the overlay
   from the Linux box, independent of the Windows firewall / AP).

### Cluster + forwarding (same as the main plan)

```bash
opendut-cleo create cluster-configuration --name traceon --leader-id <LINUX_ID> \
    --peer-ids <LINUX_ID>,<WSL_ID>
opendut-cleo create cluster-deployment --id <CLUSTER_ID>
# read the WSL peer's overlay IP (on br-opendut / wt0), then:
curl -s -X POST localhost:8082/logs/forwarding/start \
     -H 'content-type: application/json' \
     -d '{"url":"http://<overlay-wsl>:8080/internal/logs"}'
```

Pre-flight the overlay before enabling forwarding (same discipline as
TESTING-LOG-FORWARDING.md): `nc -vz <overlay-wsl> 8080` must succeed, and
`route -n get <overlay-wsl>` should go via the openDuT interface — not a VPN.

### Honest status of this appendix

- The **Linux-box side is high-confidence**: native amd64 removes the emulation
  blocker we proved on the Mac, and native EDGAR is openDuT's primary supported
  setup.
- The **WSL2 side is the risk**: EDGAR-in-WSL2 is *not* plug-and-play — the stock
  kernel's missing WireGuard/GRE/netfilter support is the likely sticking point
  (community reports consistently point to building a custom WSL2 kernel).
  Validate steps 1–2 **before** committing to this path; if WSL2 fights it, use
  a **second native Linux host** (or a Linux VM on the Windows box with proper
  networking) as the sink peer instead.
- None of this changes TRAceON code — only the forward URL points at the overlay
  IP, exactly as in the main plan.


---

## Appendix: Two native Linux machines (recommended happy path)

If both peers are **native x86_64 Linux machines**, this is the simplest and
most reliable topology — it removes every obstacle encountered elsewhere in this
document:

- **No emulation** → the amd64 CARL/Keycloak images run natively (kills the
  Mac blocker).
- **No WSL2 kernel gaps** → native kernels ship/allow WireGuard, GRE (`ip_gre`),
  and the netfilter features NetBird/EDGAR need.
- **No Docker-Desktop-on-Mac quirks** → the `/provision` bind mount and
  `grafana→loki` lean-subset issues were Mac-specific and don't occur here.

This is openDuT's primary supported setup (their own hardware guide uses Linux
hosts / Raspberry Pis for EDGAR).

### Roles

```
   AZ3166 (ThreadX) ── plain Wi-Fi / MQTT (NOT meshed) ──┐
                                                         ▼
┌──────────────────────────────┐  GRE/WireGuard  ┌──────────────────────────────┐
│ linux-a  (x86_64)             │◀═══ overlay ═══▶│ linux-b  (x86_64)             │
│  • CARL + Keycloak + NetBird  │                 │  • EDGAR peer "linux-b"       │
│  • Mosquitto broker           │                 │  • Rust log sink :8080        │
│  • telemetry server (fwd)     │                 │                               │
│  • EDGAR peer "linux-a"       │                 └──────────────────────────────┘
└──────────────────────────────┘
```

- **linux-a** hosts the backend (CARL/Keycloak/NetBird), the broker, the
  telemetry server, and one EDGAR peer.
- **linux-b** hosts the second EDGAR peer and the Rust sink.
- The **AZ3166** publishes to the broker over plain Wi-Fi (unchanged, not meshed).

> CARL may instead live on a third host (or cloud VM) that both peers can reach;
> co-locating it on linux-a is just the fewest-moving-parts option.

### Prerequisites (both machines)

```bash
# Docker + compose (for CARL on linux-a); EDGAR needs these kernel modules:
sudo modprobe wireguard && echo "wireguard ok"
sudo modprobe ip_gre    && echo "ip_gre ok"
# can-utils only if you use CAN (we don't): EDGAR setup takes --skip-can
```

### 1. linux-a — CARL backend (localenv)

```bash
git clone https://github.com/eclipse-opendut/opendut.git && cd opendut
export OPENDUT_REPO_ROOT=$(git rev-parse --show-toplevel)
# provision secrets, then bring up the stack (native amd64 — no emulation)
docker compose --file $OPENDUT_REPO_ROOT/.ci/deploy/localenv/docker-compose.yml \
  --env-file $OPENDUT_REPO_ROOT/.ci/deploy/localenv/.env.development \
  up --build provision-secrets
docker cp opendut-provision-secrets:/provision/ \
  $OPENDUT_REPO_ROOT/.ci/deploy/localenv/data/secrets/
docker compose --file $OPENDUT_REPO_ROOT/.ci/deploy/localenv/docker-compose.yml \
  --env-file $OPENDUT_REPO_ROOT/.ci/deploy/localenv/.env.development \
  --env-file $OPENDUT_REPO_ROOT/.ci/deploy/localenv/data/secrets/.env \
  up --detach --build
```
Notes vs. the Mac attempt:
- On native Linux you should **not** need the `/provision` mount repoint or the
  `grafana→loki` workaround — those were Docker-Desktop-on-Mac artifacts.
- Apply the **empty proxy build-args** only if you're behind the same corporate
  proxy (`--build-arg http_proxy= ...`); otherwise omit.
- Resolve the `*.opendut.local` domains (real DNS, or `/etc/hosts`).

### 2. linux-a — create peers + cluster via CLEO

```bash
opendut-cleo create peer --name linux-a --location lab
opendut-cleo create peer --name linux-b --location lab
opendut-cleo create network-interface --peer-id <A_ID> --type eth --name eth0
opendut-cleo create network-interface --peer-id <B_ID> --type eth --name eth0
opendut-cleo generate-setup-string --id <A_ID>   # -> SETUP_A
opendut-cleo generate-setup-string --id <B_ID>   # -> SETUP_B
```

### 3. Both machines — install EDGAR

On **linux-a** (paste SETUP_A) and **linux-b** (paste SETUP_B):
```bash
sudo ./opendut-edgar setup managed --skip-can
ip link        # verify wt0 (WireGuard) + br-opendut exist
sudo wg        # verify the WireGuard peer link
```

### 4. linux-a — define + deploy the cluster

```bash
opendut-cleo create cluster-configuration --name traceon \
    --leader-id <A_ID> --peer-ids <A_ID>,<B_ID>
opendut-cleo create cluster-deployment --id <CLUSTER_ID>
```
Deploying establishes the GRE-over-WireGuard links between the two EDGARs.

### 5. Read overlay IPs + pre-flight

```bash
# on each machine:
ip address show br-opendut      # or: ip address show wt0   -> note linux-b's overlay IP
# from linux-a, before enabling forwarding (same discipline as TESTING-LOG-FORWARDING.md):
nc -vz -w 3 <overlay-linux-b> 8080           # must succeed
route -n get <overlay-linux-b> | grep interface   # must be the openDuT iface, not a VPN
```

### 6. Point forwarding at the overlay IP

```bash
# linux-b: start the Rust sink on 0.0.0.0:8080 (reachable over the overlay)
# linux-a: start the telemetry server, then:
curl -s -X POST localhost:8082/logs/forwarding/start \
     -H 'content-type: application/json' \
     -d '{"url":"http://<overlay-linux-b>:8080/internal/logs"}'
```

Data path: AZ3166 → linux-a broker (Wi-Fi) → telemetry server → **POST over the
openDuT overlay** → Rust sink on linux-b. No dependence on the local LAN between
the two Linux boxes — openDuT tunnels it, so AP isolation / host firewalls are
irrelevant.

### Why this is the low-risk option

| Concern | Mac-only | Linux + WSL2 | **Two native Linux** |
|---|---|---|---|
| CARL/Keycloak images | amd64 **emulated → blocked** | native on Linux side | **native ✓** |
| EDGAR runnable | **no (macOS)** | WSL2: needs custom kernel | **native ✓** |
| WireGuard/GRE/netfilter | n/a | WSL2 gaps likely | **stock/available ✓** |
| Docker mount/network quirks | several (Mac) | some (WSL) | **none ✓** |
| TRAceON code changes | none | none | **none** (forward URL → overlay) |

Everything the earlier appendices flagged as risky collapses to "just works" with
two native Linux hosts. The only TRAceON-side change remains the forward URL
pointing at the overlay IP.
