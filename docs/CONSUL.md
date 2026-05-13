# Consul as a parallel health-check plane

← back to [README](../README.md)

Alongside the broker, every backend and every BFF also registers itself with a **Consul** dev-mode container. Consul probes each registered service independently:

| Tier | Consul check | Interval | Auto-deregister after |
|---|---|---|---|
| Backends | `Check.GRPC: server-x:9101` (uses the same `grpc.health.v1.Health` we already expose) | 2 s | 30 s of failures |
| BFFs | `Check.HTTP: http://bff-x:8080/api/internal/health` | 2 s | 30 s of failures |

This layer is **purely additive** in this slice — routing decisions still go through the broker + per-BFF `Health.Watch`. Consul gives a second, independent view of cluster health with a built-in UI at <http://localhost:8500/ui/>.

## Why both broker and Consul?

Three reasons:

1. **A safety net at a different layer.** If our broker were to misbehave, Consul's view still shows ground truth via active probes.
2. **Broker recovery without re-announces.** The broker queries Consul on startup (`GET /v1/health/service/{name}?passing`) and pre-seeds its in-memory map before opening its listener. A broker restart no longer requires every node to re-announce — the cluster view is reconstructed from Consul's catalog in milliseconds. See [`BrokerApp.java`](../lifecycle-broker/src/main/java/com/example/poc/broker/BrokerApp.java) and [`ConsulBootstrap.java`](../lifecycle-broker/src/main/java/com/example/poc/broker/ConsulBootstrap.java).
3. **A natural migration path.** Future slices can incrementally route via Consul's catalog (BFF watch loops, `consul-template` for nginx, ACLs/mTLS via Consul Connect) until the broker is no longer needed.

## Responsibility split

| Concern | Broker | Consul |
|---|---|---|
| Service registration on boot | ✓ `Announce` from every node | ✓ `PUT /agent/service/register` from every node |
| Graceful deregistration on shutdown | ✓ `Withdraw` from shutdown hook | ✓ `PUT /agent/service/deregister/{id}` |
| Abrupt-death detection (backends) | ✓ Per-BFF `grpc.health.v1.Health/Watch` stream | ✓ Per-backend `Check.GRPC` probe |
| Abrupt-death detection (BFFs) | ✓ Subscription stream breaks → auto-LEFT | ✓ `Check.HTTP /api/internal/health` |
| Subscriber-side fan-out of changes | ✓ Streaming `Subscribe` RPC to every BFF | (BFFs don't watch Consul yet) |
| **Driving routing in BFFs** | ✓ **only the broker** — `BrokerSubscriber` → `ServerRegistry` → `RoundRobinRouter` | — |
| Cross-role visibility for the UI | ✓ `KnownNodesRegistry` populated from broker events | — |
| External UI / dashboard | — | ✓ <http://localhost:8500/ui/> |
| HA / Raft / persistence | ✗ single-instance, in-memory | (dev mode here; the model supports HA when you add servers) |

If you stopped the broker right now, requests would keep working (existing channels), but new node discovery would freeze until it came back. Stop Consul and nothing in the data path is affected at all.

## Code

Registration:
- [`grpc-server/.../ConsulRegistrar.java`](../grpc-server/src/main/java/com/example/poc/server/ConsulRegistrar.java) — registers backends with `Check.GRPC`.
- [`bff/.../consul/ConsulRegistrar.java`](../bff/src/main/java/com/example/poc/bff/consul/ConsulRegistrar.java) — registers BFFs with `Check.HTTP`.

Both use Java's stdlib `java.net.http.HttpClient` and `PUT /v1/agent/service/register` / `PUT /v1/agent/service/deregister/{id}` on the Consul HTTP API. No new dependencies.

Broker bootstrap:
- [`BrokerApp.java`](../lifecycle-broker/src/main/java/com/example/poc/broker/BrokerApp.java) — calls `ConsulBootstrap.seed()` before `server.start()`.
- [`ConsulBootstrap.java`](../lifecycle-broker/src/main/java/com/example/poc/broker/ConsulBootstrap.java) — reads `GET /v1/health/service/{name}?passing` and parses with Jackson (the one place we need real JSON parsing).

## Quick check

```bash
# Cluster snapshot
curl -s http://127.0.0.1:8500/v1/agent/services | jq '. | to_entries | .[] | .value | {service: .Service, id: .ID, tags: .Tags}'

# Health of every echo-server
curl -s http://127.0.0.1:8500/v1/health/service/echo-server \
    | jq '.[] | {id: .Service.ID, checks: [.Checks[] | {name, status}]}'

# Just the BFFs
curl -s http://127.0.0.1:8500/v1/health/service/bff \
    | jq '.[] | {id: .Service.ID, status: [.Checks[] | .Status]}'
```

## Verifying the broker bootstrap path

After cold-start, the broker's log shows:

```
Bootstrapping from Consul at consul:8500...
Consul bootstrap: 0 nodes seeded (0 SERVER, 0 BFF)   # broker started before anyone announced
LifecycleBroker listening on :7100
```

Then if you `./bin/c restart broker`:

```
Bootstrapping from Consul at consul:8500...
Consul bootstrap: 8 nodes seeded (4 SERVER, 4 BFF)   # Consul has everyone now
LifecycleBroker listening on :7100
```

Within a few seconds of restart, BFFs reconnect their `Subscribe` streams and receive a fully populated snapshot — no node has to re-announce. The `test_broker_restart_bootstraps_from_consul` integration test ([TESTS.md § 19](../TESTS.md)) asserts this exact property.

## What's not yet wired through Consul

Currently Consul is an **observer**, not a driver. Future slices could replace each broker responsibility one at a time:

- BFFs swap `BrokerSubscriber` for a Consul blocking-query watch loop (`GET /v1/health/service/{name}?passing&tag={role}&index=N&wait=30s`).
- nginx upstreams driven by `consul-template` (no more sed + reload to add a BFF).
- The lifecycle broker module deleted entirely once both above land.

See the "not in scope" bullets in [OPERATIONS.md](OPERATIONS.md#things-that-arent-in-scope) for the full list.

---

Related: [ARCHITECTURE.md](ARCHITECTURE.md) for the broker side · [TESTS.md](../TESTS.md) for what the suite verifies.
