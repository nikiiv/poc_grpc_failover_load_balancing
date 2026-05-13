# Operations — running, testing, API

← back to [README](../README.md)

## Run

```bash
./bin/compose up --build
# open http://localhost:5173
```

The wrappers in `bin/c` and `bin/compose` auto-detect `docker` (if its daemon is reachable) or fall back to `podman`. Docker ≥ 24 and Podman ≥ 5 are supported.

### Demo commands

```bash
./bin/demo                                         # narrated walk-through (Enter between steps)
./bin/demo --auto                                  # same but non-interactive (smoke test)

./bin/compose --profile extra up -d server-t-3     # add a third trading backend live
./bin/compose --profile extra up -d bff-t-3        # add a third trading BFF live (also needs nginx reload — see below)
./bin/c kill server-t-1                            # SIGKILL — abrupt server failure
./bin/c stop server-t-1                            # SIGTERM — fires the shutdown hook
./bin/c start server-t-1                           # restart a stopped container
./bin/c kill bff-t-1                               # take down a BFF — nginx fails over
./bin/compose down                                 # stop everything
./bin/compose --profile extra down                 # ...including the extra-profile containers
```

To wire `bff-t-3` into nginx after starting it (see [ARCHITECTURE.md § Adding upstreams to nginx live](ARCHITECTURE.md#adding-upstreams-to-nginx-live) for the rationale):

```bash
./bin/c exec ui sed -i 's|# server bff-t-3|server bff-t-3|' /etc/nginx/conf.d/default.conf
./bin/c exec ui nginx -s reload
```

### Mac under Podman, first time

```bash
podman machine init --cpus 4 --memory 6144
podman machine start
```

## Tests

```bash
./bin/test                         # bring up, run all 20 tests, tear down (~4 min)
./bin/test --existing              # run against an already-running stack
./bin/test --keep-stack            # don't tear down at the end (debugging)
./bin/test --list                  # show every test in suite order
./bin/test test_kill_server_drops_from_bff   # run one
```

The suite covers:

- **Baseline** — all 11 containers up, Consul + broker reachable, both buckets self-registered with 2 servers each, basic echo round-trips, role-isolation (a billing echo never hits a trading server), cross-role known-nodes view populated.
- **Server lifecycle** — `kill -9` drops the backend from the BFF within ~2 s, Consul flips its gRPC check to `critical` within 2 s, a billing kill leaves the trading bucket untouched, graceful drain removes a server cleanly.
- **BFF lifecycle** — `kill -9` a BFF; nginx fails over with ≥ 8 of 10 follow-up echoes succeeding (1–2 failures are expected during the `fail_timeout` retry window). Consul flips the HTTP check to `critical` within 2 s.
- **Broker resilience** — `docker restart broker` triggers a fresh `Consul bootstrap` (non-zero seed count), and BFFs converge to 8 known nodes again within 15 s.
- **Scale-out** — a brand-new `server-t-3` brought up via the `extra` profile is in the trading pool within 15 s of joining.

Every destructive test restores baseline state before returning, so subsequent tests start fresh. Total runtime is ~3–4 min on cold start (less with `--existing`).

For the full per-test rationale and pass criteria, see [TESTS.md](../TESTS.md).

## Native dev (faster iteration, single BFF)

JDK 17 and Node 20.

```bash
./gradlew start_broker        # gRPC :7100
./gradlew start_bff_t1        # HTTP :8080, ROLE=trading
./gradlew start_server_t1     # :9101
./gradlew start_server_t2     # :9102
./gradlew start_ui            # Vite dev server :5173
```

In native mode the UI talks straight to `bff-t-1` (no nginx). Hit `http://127.0.0.1:8080/api/echo` directly to verify, or switch the Vite proxy in `web-client/vite.config.ts` if you want path-prefix routing locally.

## API reference

All paths are role-prefixed; replace `{role}` with `trading` or `billing`.

| Method | Path | Body | Purpose |
|---|---|---|---|
| `GET` | `/api/{role}/servers` | — | Same-role routable pool |
| `GET` | `/api/{role}/known-nodes` | — | Full cluster topology (all roles, all kinds) |
| `GET` | `/api/{role}/identity` | — | Which BFF you've landed on (`{nodeId, role}`) |
| `POST` | `/api/{role}/echo` | `{"message": "..."}` | Routes to a healthy same-role backend |
| `POST` | `/api/{role}/compute` | `{"workMs": 300}` | Slow request (for drain visuals) |
| `POST` | `/api/{role}/servers/{id}/drain` | — | Tells a backend to drain gracefully |
| `GET` | `/api/{role}/events` | — | SSE stream of registry + topology events |

Internal (not exposed through nginx, used by Consul probes):

| Method | Path | Purpose |
|---|---|---|
| `GET` | `/api/internal/health` | Always returns 200 OK if the BFF process is alive |

## Ports

| Service | Port | Where exposed |
|---|---|---|
| UI / front door | 5173 | Host → nginx :80 in the `ui` container |
| Broker | 7100 | Host → `broker` (for `gradle start_*` from outside compose) |
| Consul HTTP + UI | 8500 | Host → `consul` |
| Consul DNS | 8600/udp | Host → `consul` (optional) |
| BFFs HTTP | 8080 | Internal only — fronted by nginx |
| Backends gRPC | 9101 | Internal only |

Note: **7100, not 7000** — macOS Control Center / AirPlay Receiver binds 7000 by default.

## Things that aren't in scope

- TLS — everything is plaintext.
- Persistent state — registries (broker and BFF) are all in-memory.
- Broker redundancy — single instance. If it dies, existing routes keep working; a new broker process bootstraps the cluster view from Consul on restart, so the gap is short. Multiple brokers + Raft would be the production fix.
- Retry / backoff on the `EchoStub.echo` call — single attempt; failure returns 502.
- Authentication / authorization on the broker — anyone reachable can announce or withdraw a node.
- Per-service health (`grpc.health.v1.Health` supports watching individual service names) — we only use the overall `""` service watch.
- Fully-automatic BFF discovery in nginx — OSS nginx needs an edit-and-reload to add a new upstream. Traefik / nginx Plus / Envoy fix this; using one of those would be a future enhancement.
- Consul-driven *routing* — backends and BFFs register with Consul today, but BFFs still route via the broker. A future slice can replace the broker subscriber with a Consul blocking-query watch loop, and add `consul-template` to drive nginx upstreams.
- Consul HA — single-node dev mode. Production would run 3+ Consul servers with Raft.
