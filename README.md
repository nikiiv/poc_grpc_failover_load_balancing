# gRPC failover & load balancing — POC

A demonstration of dynamic service discovery, gRPC health checking, abrupt-failure detection, graceful drain, and **multi-BFF / multi-role redundancy** across a small stack:

**React/Vite UI → nginx front door → 2 BFFs per role → N × Java gRPC backends per role**, coordinated through a small pub/sub **lifecycle broker** and observed in parallel by **Consul**.

Runs under either **Docker** or **Podman** with auto-detection; the same image set works on Linux and macOS.

## Try it in 30 seconds

```bash
./bin/compose up --build
# open http://localhost:5173        — the app
# open http://localhost:8500/ui/    — Consul health dashboard
```

Then try breaking it:

```bash
./bin/c kill server-t-1                          # SIGKILL — backend gone in ~1 s
./bin/c kill bff-t-1                             # SIGKILL — nginx fails over silently
./bin/c restart broker                           # broker re-seeds from Consul on boot
./bin/compose --profile extra up -d server-t-3   # add a backend live
```

For the guided tour and the test suite:

```bash
./bin/demo --auto    # narrated walk-through of every key feature, ~3 min
./bin/test           # run the 20-test integration suite, ~4 min
```

## Documentation

| Doc | What's in it |
|---|---|
| [docs/PROBLEM.md](docs/PROBLEM.md) | The problem this POC solves, what it demonstrates, and the live demo walk-through (business-facing). |
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | Full technical deep-dive — broker pub/sub, BFF subscribe loop, health watching, kill detection, graceful drain, nginx front door, SSE event stream, module-by-module summary. |
| [docs/CONSUL.md](docs/CONSUL.md) | The parallel Consul health-check plane — registration, broker bootstrap on restart, responsibility split with the broker. |
| [docs/OPERATIONS.md](docs/OPERATIONS.md) | Run, demo, test, native dev, API reference, ports, what's not in scope. |
| [TESTS.md](TESTS.md) | Per-test documentation for the 20-test integration suite. |

## Quick architecture sketch

```
                       React/Vite UI  (:5173)
                              │
                              ▼
                       nginx (front door)
                       /api/trading/* ─┐    /api/billing/* ─┐
                                       │                    │
                                       ▼                    ▼
                              ┌────────────────┐   ┌────────────────┐
                              │  trading BFFs  │   │  billing BFFs  │
                              │  bff-t-1 / -2  │   │  bff-b-1 / -2  │
                              └─┬─┬──────┬─────┘   └─┬─┬──────┬─────┘
                                │ │      │           │ │      │
                       gRPC echo│ │ ann/sub          │ │ann/sub
                                ▼ │      ▼           ▼ │      ▼
                  ┌─────────────────┐  ┌──────────────────────┐
                  │ trading backends │  │  Lifecycle Broker    │
                  │ server-t-1 / -2  │  │     (:7100)          │
                  │ billing backends │  │  pub/sub control     │
                  │ server-b-1 / -2  │  │  plane               │
                  └────────┬─────────┘  └──────┬───────────────┘
                           │                   │
                           │ register +        │ bootstrap on start
                           │ health-probed by  │ + parallel probes
                           ▼                   ▼
                       ┌──────────────────────────┐
                       │       Consul  (:8500)    │
                       │  catalog + active probes │
                       └──────────────────────────┘
```

- The **broker drives routing**: BFFs `Subscribe` to its event stream and use the resulting view to build their per-backend channels.
- **Consul is the durable source of truth**: every backend and BFF registers with it on boot; the broker re-seeds from Consul whenever it restarts, so the cluster view survives a cold reboot without anyone re-announcing.
- The broker layer is *fast and gRPC-native* (streaming, sub-second propagation); the Consul layer is *resilient and authoritative* (HTTP, active probes, restart-survivable). They complement each other.

## Status

- Branch: `consul_for_health`
- Test suite: 20 / 20 passing on cold start (see [TESTS.md](TESTS.md))
- Containers: 11 — broker, Consul, 2 × trading BFFs, 2 × trading backends, 2 × billing BFFs, 2 × billing backends, UI

## Not production-ready

Deliberate POC scope — no TLS, no authentication, single-instance broker, single-node Consul (dev mode). The patterns it shows are the same ones used in production gRPC systems at scale.

Cold-reboot recovery for routing-critical state *is* covered: when the broker restarts it bootstraps its cluster view from Consul (see [docs/CONSUL.md](docs/CONSUL.md)), and BFFs replace their in-memory registries from the broker's snapshot on reconnect. Only cosmetic state (the UI's recent-request log, RPS window) is lost on restart.

See [docs/OPERATIONS.md § Things that aren't in scope](docs/OPERATIONS.md#things-that-arent-in-scope) for the full list.
