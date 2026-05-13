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
React/Vite UI ──── nginx (front door) ─┬──► trading BFFs ──► trading backends
                                       └──► billing BFFs ──► billing backends
                                              │  ▲
                                       Announce/Subscribe
                                              │  │
                                       ┌──────▼──┴───┐
                                       │  Lifecycle  │
                                       │   Broker    │◄── bootstraps from ┐
                                       └─────────────┘                    │
                                                                    ┌─────▼─────┐
                                       (all nodes also              │  Consul   │
                                        register with) ────────────►│ (probes)  │
                                                                    └───────────┘
```

The broker drives **routing**; Consul provides **independent observability** and is the source of truth that the broker rebuilds itself from on restart.

## Status

- Branch: `consul_for_health` (4 commits ahead of `main`)
- Test suite: 20 / 20 passing on cold start
- Containers: 11 (broker, Consul, 2 trading BFFs, 2 trading backends, 2 billing BFFs, 2 billing backends, UI)

## Not production-ready

Deliberate POC scope — no TLS, no authentication, single-instance broker, in-memory state. The patterns it shows are the same ones used in production gRPC systems at scale. See [docs/OPERATIONS.md § Things that aren't in scope](docs/OPERATIONS.md#things-that-arent-in-scope) for the full list.
