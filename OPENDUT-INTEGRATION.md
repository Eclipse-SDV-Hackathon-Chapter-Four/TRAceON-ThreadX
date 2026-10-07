# Plan: Testing TRAceON components with Eclipse openDuT (two WSL machines)

**Status:** proposal / next-steps (not yet implemented).
**Goal:** use Eclipse **openDuT** to give TRAceON a reliable, repeatable,
network-agnostic test setup — specifically to make the **log-forward hop between
two machines** work regardless of the local network, solving the cross-machine
reachability problems we hit at the venue (AP client isolation, host firewalls,
NAT) without touching any TRAceON code.

**Assumed topology (decided):** **two Windows machines, each running WSL2
(Ubuntu).** This matches the hardware we actually have for the demo. openDuT's
`EDGAR` agent and the CARL backend run **inside** WSL2 on each machine. WSL2 is
viable but needs some one-time setup the native-Linux path doesn't — see
"WSL2 prerequisites (read first)" below, which is the make-or-break section.

> A fully-supported alternative is **two native x86_64 Linux machines** (openDuT's
> primary supported setup). If the WSL kernel/networking steps below prove too
> fiddly on the day, fall back to native Linux — the plan is otherwise identical.
> See the "Native Linux fallback" note.

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

**How the mesh works (from the docs):** EDGAR tunnels **Layer-2 Ethernet** traffic
between peers using **GRE encapsulated in WireGuard** (star topology, relayed via
CARL if no direct path), bridging a peer's interface into `br-opendut` so peers
reach each other over the overlay regardless of physical location.

### Honest caveats for TRAceON

1. **EDGAR is Linux-only and host-side** (it runs **inside WSL2** on each Windows
   machine). openDuT meshes the two WSL hosts — i.e. the
   **computer↔computer forward hop** between the telemetry server and the sink.
2. **openDuT only rescues the server → sink forward hop.** It makes that
   cross-machine leg work regardless of the local network; it does not touch how
   logs arrive at the telemetry server in the first place.
3. **Deployment is non-trivial:** CARL brings up Keycloak + NetBird (+ optional
   telemetry) via Docker Compose. A real deployment, best treated as
   post-hackathon work.

### WSL2 prerequisites (read first)

EDGAR needs kernel features the **stock WSL2 kernel does not ship** (WireGuard,
`ip_gre`, and the nftables/netfilter bits WireGuard routing relies on). On each
Windows machine you must prepare WSL2 before anything openDuT-related will work:

1. **Custom WSL2 kernel with WireGuard + GRE + netfilter.** The Microsoft default
   kernel lacks these. Build/install a custom WSL2 kernel that enables
   `CONFIG_WIREGUARD`, `CONFIG_NET_IPGRE`/`CONFIG_NET_IPGRE_DEMUX`, and the
   nftables modules, then point `%UserProfile%\\.wslconfig` at it:
   ```ini
   [wsl2]
   kernel=C:\\path\\to\\bzImage-wireguard
   ```
   Verify inside WSL after `wsl --shutdown` + restart:
   ```bash
   sudo modprobe wireguard && echo "wireguard ok"
   sudo modprobe ip_gre    && echo "ip_gre ok"
   ```
   (Prebuilt custom-kernel recipes exist; see the open-source WSL2 kernel builders.)

2. **Mirrored networking mode** so the two WSL instances get host-routable IPs
   instead of being double-NATed behind each Windows host:
   ```ini
   [wsl2]
   networkingMode=mirrored
   ```
   Caveat (known issue): WireGuard **between two WSL2 instances with mirrored
   mode** has reported failures — this is the single biggest risk in the WSL
   path. Validate host↔host reachability early (Phase 0.5 below) before investing
   in the full stack.

3. **Windows Defender Firewall** must allow the WSL vEthernet traffic / the CARL
   ports between the two machines. The openDuT overlay rides WireGuard, but CARL
   relaying and the initial peer handshake still need the two Windows hosts to
   reach each other.

4. **systemd in WSL** (for running EDGAR/Docker cleanly):
   `/etc/wsl.conf` → `[boot]` with `systemd=true`, then `wsl --shutdown`.

### WSL2 vs the alternatives

| Concern | macOS (tried) | **Two WSL2 machines** | Two native Linux (fallback) |
|---|---|---|---|
| CARL/Keycloak images (amd64) | **emulated → blocked** | native on x86_64 Windows ✓ | native ✓ |
| EDGAR runnable | **no (macOS)** | yes, with custom kernel ✓ | native ✓ |
| WireGuard/GRE/netfilter | n/a | **custom WSL2 kernel required** | stock/available ✓ |
| Host↔host networking | n/a | **mirrored mode (known WG caveat)** | plain LAN ✓ |
| TRAceON code changes | none | none | none |

The macOS path is a proven dead end (amd64-under-emulation; EDGAR has no macOS
build at all — see the historical note at the bottom). **Two WSL2 machines** work
once the custom kernel + mirrored networking are in place; the WireGuard-between-
WSL caveat is the thing to de-risk first. **Two native Linux hosts** remain the
zero-friction fallback if WSL fights back on the day.

### Native Linux fallback

If Phase 0 or 0.5 fails on WSL (custom kernel won't take, or WSL-to-WSL WireGuard
won't come up under mirrored mode), switch to **two native x86_64 Linux machines**
and skip the entire "WSL2 prerequisites" section — `wireguard`/`ip_gre` and plain
LAN reachability are available out of the box. Every Phase from 1 onward is
identical; only the host labels change (read `wsl-a`/`wsl-b` as your two Linux
hosts). This is openDuT's primary supported setup (their hardware guide uses
Linux hosts / Raspberry Pis for EDGAR).

---

## Target topology

```
┌──────────────────────────────┐  GRE/WireGuard  ┌──────────────────────────────┐
│ wsl-a  (Ubuntu on Windows)    │◀═══ overlay ═══▶│ wsl-b  (Ubuntu on Windows)    │
│  • CARL + Keycloak + NetBird  │                 │  • EDGAR peer "wsl-b"         │
│  • Mosquitto broker (1883)    │                 │  • Rust log sink :8080        │
│  • telemetry server (:8082)   │                 │                               │
│  • EDGAR peer "wsl-a"         │                 └──────────────────────────────┘
│  (custom kernel + mirrored)   │
└──────────────────────────────┘
```

- **wsl-a** hosts the backend (CARL/Keycloak/NetBird), the broker, the
  telemetry server, and one EDGAR peer.
- **wsl-b** hosts the second EDGAR peer and the Rust sink.
- The forward hop **wsl-a → wsl-b** rides the openDuT overlay; the local network
  between the two Windows machines is irrelevant.

> CARL may instead live on a third host (or cloud VM) both peers can reach;
> co-locating it on wsl-a is just the fewest-moving-parts option. A reachable
> CARL also lets it relay WireGuard when peers have no direct path.

---

## Plan

### Phase 0 — Prerequisites (both machines)
```bash
# Docker + Docker Compose (for CARL, on wsl-a).
# EDGAR needs these kernel modules on each machine:
sudo modprobe wireguard && echo "wireguard ok"
sudo modprobe ip_gre    && echo "ip_gre ok"
# can-utils only if you use CAN (we don't): EDGAR setup takes --skip-can
```
> If either `modprobe` fails, your WSL2 kernel is the stock one — go back to
> "WSL2 prerequisites" and install the custom kernel first. Nothing below works
> until both load.

### Phase 0.5 — WSL: prove host↔host WireGuard works (de-risk FIRST)
Before building the whole openDuT stack, confirm the WSL caveat isn't going to
bite. Stand up a trivial point-to-point WireGuard tunnel between the two WSL
instances (mirrored networking) and ping across it:
```bash
# wsl-a and wsl-b: wg genkey/pubkey, a minimal wg0 (10.9.0.1 / 10.9.0.2),
# AllowedIPs, Endpoint = the OTHER Windows host's mirrored IP, then:
sudo wg-quick up wg0
ping -c3 10.9.0.2        # from wsl-a  (and 10.9.0.1 from wsl-b)
```
If this ping succeeds, the openDuT overlay (same primitives) will too. If it
fails even with mirrored mode, **switch to the native-Linux fallback now** rather
than discovering it mid-stack. Tear the test tunnel down (`wg-quick down wg0`).

### Phase 1 — wsl-a: deploy CARL (backend)
```bash
git clone https://github.com/eclipse-opendut/opendut.git && cd opendut
export OPENDUT_REPO_ROOT=$(git rev-parse --show-toplevel)
# provision secrets
docker compose --file $OPENDUT_REPO_ROOT/.ci/deploy/localenv/docker-compose.yml \
  --env-file $OPENDUT_REPO_ROOT/.ci/deploy/localenv/.env.development \
  up --build provision-secrets
docker cp opendut-provision-secrets:/provision/ \
  $OPENDUT_REPO_ROOT/.ci/deploy/localenv/data/secrets/
# bring up the stack (CARL + Keycloak + NetBird + LEA [+ telemetry])
docker compose --file $OPENDUT_REPO_ROOT/.ci/deploy/localenv/docker-compose.yml \
  --env-file $OPENDUT_REPO_ROOT/.ci/deploy/localenv/.env.development \
  --env-file $OPENDUT_REPO_ROOT/.ci/deploy/localenv/data/secrets/.env \
  up --detach --build
```
- On x86_64 Windows the amd64 images run natively **inside WSL2** — no emulation,
  so the Keycloak / CARL-init slowness we hit on the Mac does not occur.
- Resolve the `*.opendut.local` domains (real DNS, or `/etc/hosts` → the host IP).
- Secrets (incl. the CLEO OIDC client secret + the CA at `secrets/pki/`) land in
  `.ci/deploy/localenv/data/secrets/`.
- **Only if** you're behind a corporate proxy that injects into Docker, pass empty
  proxy build-args: `--build-arg http_proxy= --build-arg https_proxy=
  --build-arg HTTP_PROXY= --build-arg HTTPS_PROXY=` (see historical note).

### Phase 2 — wsl-a: configure CLEO
```bash
export OPENDUT_CLEO_NETWORK_CARL_HOST=opendut.local
export OPENDUT_CLEO_NETWORK_CARL_PORT=443
export OPENDUT_CLEO_NETWORK_OIDC_CLIENT_ID=opendut-cleo-client
export OPENDUT_CLEO_NETWORK_OIDC_CLIENT_SECRET=<from secrets/.env>
export SSL_CERT_FILE=.../secrets/pki/opendut-ca.pem    # the openDuT CA
opendut-cleo list peers      # smoke test
```

### Phase 3 — wsl-a: create the two peers
```bash
opendut-cleo create peer --name wsl-a --location lab   # backend + server + sink-source
opendut-cleo create peer --name wsl-b --location lab   # sink host
opendut-cleo list peers      # note the two PeerIDs: <A_ID>, <B_ID>
```

### Phase 4 — add a network interface to each peer
openDuT requires an interface to bridge; use the Ethernet form.
```bash
opendut-cleo create network-interface --peer-id <A_ID> --type eth --name eth0
opendut-cleo create network-interface --peer-id <B_ID> --type eth --name eth0
```
> Our forwarding only needs host↔host IP reachability over the overlay, not a
> bridged DuT. The interface is required by the model; the overlay IPs are what
> we actually use.

### Phase 5 — generate setup-strings + install EDGAR on each host
```bash
opendut-cleo generate-setup-string --id <A_ID>   # -> SETUP_A
opendut-cleo generate-setup-string --id <B_ID>   # -> SETUP_B
```
Download EDGAR from LEA → Downloads, then on each machine run the managed setup
with that host's string:
```bash
sudo ./opendut-edgar setup managed --skip-can     # paste SETUP_A on wsl-a, SETUP_B on wsl-b
ip link        # verify wt0 (WireGuard) + br-opendut appear
sudo wg        # verify the WireGuard peer link
```

### Phase 6 — create + deploy the cluster (on wsl-a)
```bash
opendut-cleo create cluster-configuration --name traceon \
    --leader-id <A_ID> --peer-ids <A_ID>,<B_ID>      # confirm exact flags via --help
opendut-cleo list cluster-configurations             # note <CLUSTER_ID>
opendut-cleo create cluster-deployment --id <CLUSTER_ID>
```
Deploying establishes the GRE-over-WireGuard links between the two EDGARs.

> Alternatively declare peers + cluster in one YAML and `opendut-cleo apply <file>`
> (kubectl-style); the manual documents the `PeerDescriptor` / `ClusterDescriptor`
> schema.

### Phase 7 — read overlay IPs + pre-flight
```bash
# on each machine:
ip address show br-opendut      # or: ip address show wt0   -> note wsl-b's overlay IP
# from wsl-a, before enabling forwarding (same discipline as TESTING-LOG-FORWARDING.md):
nc -vz -w 3 <overlay-wsl-b> 8080                 # must say "succeeded!"
route -n get <overlay-wsl-b> | grep interface    # must be the openDuT iface, not a VPN
```

### Phase 8 — point forwarding at the overlay IP
**Only runtime config changes — no server code change.**
```bash
# wsl-b: start the Rust sink on 0.0.0.0:8080 (reachable over the overlay)
# wsl-a: start the telemetry server, then:
curl -s -X POST localhost:8082/logs/forwarding/start \
     -H 'content-type: application/json' \
     -d '{"url":"http://<overlay-wsl-b>:8080/internal/logs"}'
```
or bake it in at launch:
`TRACEON_LOG_FORWARD_URL=http://<overlay-wsl-b>:8080/internal/logs`.

Data path: **telemetry server (wsl-a) → POST over the openDuT overlay → Rust sink
on wsl-b.** The local network between the two Windows machines is irrelevant —
openDuT tunnels it, so AP isolation / host firewalls / NAT don't matter.

---

## Optional: automated test via openDuT (VIPER / container-executor)

openDuT runs **containerized test applications** on a peer and uploads results to
a WebDAV dir (`/results/` + a `.results_ready` marker). We could codify a
**forward-and-verify** test:
```bash
opendut-cleo create container-executor \
    --peer-id <B_ID> --engine docker \
    --name traceon-forward-check --image <our-test-image> \
    --results-url http://nginx-webdav.opendut.local/
```
The test container would: run the sink, trigger log emission (e.g.
`send-log.sh`), assert the sink received the expected ISO LogEntries with the
right DLT severities, then write results. Deploying the cluster triggers it.

---

## What changes vs. stays the same

| Piece | Change for openDuT? |
|---|---|
| Mosquitto broker | None |
| Telemetry server (Py/Java) code | **None** |
| Rust sink code | None |
| **Forward URL** | `192.168.x` → **overlay IP of wsl-b** (only functional change) |
| New infra | CARL+Keycloak+NetBird on wsl-a; EDGAR in both WSL instances |

## Open items to validate early
- **WSL2 custom kernel** on both machines: `wireguard` + `ip_gre` must `modprobe`
  (Phase 0). Stock WSL2 kernels fail here.
- **Host↔host WireGuard under mirrored networking** (Phase 0.5): the known
  WSL-to-WSL WireGuard caveat is the top risk — prove it before building the
  stack, or fall back to native Linux.
- **Windows Firewall** between the two machines (CARL ports + the WireGuard
  endpoint port).
- **Exact CLEO flag names** (`--peer-ids` / `--leader-id` / cluster subcommand
  spelling) — confirm with `--help` on the installed version.
- **CARL reachability**: both EDGARs must reach CARL; if the two machines are on
  different networks, host CARL somewhere both can reach (it can also relay the
  WireGuard tunnel).

---

## Historical note — the macOS attempt (2026-10-07, why we moved off macOS)

We first tried a **Mac-only** bring-up of the CARL backend (Apple Silicon, arm64).
It is recorded here because the fixes are reusable and the blocker is the reason
we standardized on Linux.

**What worked on the Mac:** cloned the repo; `provision-secrets` + the image build
succeeded **once the injected Docker proxy was neutralized** (empty proxy
build-args — same issue as our own images; without it `apt-get` fails "Unable to
locate package"). Core services came up healthy (traefik, keycloak,
keycloak-postgres, netbird-signal/relay); `keycloak-init` completed.

**Docker-Desktop-on-Mac-specific fixes we needed (NOT needed on native Linux):**
1. **Proxy** — empty proxy build-args.
2. **Lean subset** — `OPENDUT_LOCALENV_TELEMETRY_ENABLED=0` zeroes the Grafana/
   Prometheus/Loki/Tempo/Alloy/otel replicas, but `grafana depends_on loki` then
   fails `up`; workaround was starting core services by name with `--no-deps`.
3. **Cert bind mount** — compose binds host path `/provision`
   (`SHARED_CERTS_HOST_DIR=/provision`), which isn't shared on Docker Desktop for
   Mac → "mounts denied"; workaround was overriding `SHARED_CERTS_HOST_DIR` to the
   real host PKI dir (`.ci/deploy/localenv/data/secrets/pki`).

**The blocker that ended the Mac path:** the localenv images are **amd64-only**;
on arm64 they run under **QEMU emulation**. Emulated **Keycloak was far too slow**
(traefik logged ~3 s/request, `DownstreamStatus 499`), so CARL's init loop
(**"Waiting for https://auth.opendut.local/…"**) timed out (~6 s) and CARL never
became `healthy`. This is an architecture/emulation wall, not a config bug — and
separately, **EDGAR has no macOS build at all**, so the Mac could never host a
mesh peer regardless. Hence we target **two WSL2 machines**, with native Linux as the fallback.

**Teardown used:** `docker compose … down --remove-orphans` (our `traceon-broker`
is a separate stack and was unaffected).
