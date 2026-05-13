# Technical implementation

← back to [README](../README.md)

## Architecture overview

```
                  ┌─────────────────────────────────────┐
                  │      Web browser  (your laptop)     │
                  │       Loads SPA, makes /api calls   │
                  └──────────────────┬──────────────────┘
                                     │ HTTP + SSE  (port 5173)
                                     ▼
                  ┌─────────────────────────────────────┐
                  │     `ui` container — nginx          │
                  │  (a) GET /             → SPA bundle │
                  │  (b) /api/trading/*    → upstream A │
                  │  (c) /api/billing/*    → upstream B │
                  └──────┬───────────────────────┬──────┘
                         │                       │
                         ▼                       ▼
       ┌────────────────────┐         ┌────────────────────┐
       │  trading BFFs       │         │  billing BFFs       │
       │  bff-t-1, bff-t-2   │         │  bff-b-1, bff-b-2   │
       └─────┬────────┬──────┘         └─────┬────────┬─────┘
             │        │   ┌────────────┐     │        │
             │        │   │  Lifecycle │     │        │
             │ ─Announce/─►   Broker   │◄─Subscribe─  │
             │ Subscribe │  (:7100)    │ Announce─────┤
             │        │   └─────┬──────┘     │        │
             │        │         │            │        │
             ▼        ▼         │            ▼        ▼
      ┌────────────┬────────────┴───────────────────────┐
      │ server-t-1 │ server-t-2     server-b-1 server-b-2 │
      │ (role=trading)               (role=billing)         │
      │  Echo / Compute / Drain / grpc.health.v1.Health     │
      └─────────────────────────────────────────────────────┘
```

Key shapes:

- The **lifecycle broker** is a separate gRPC service. It's a dumb fan-out: every BFF and every backend announces itself there once on boot; every BFF subscribes to a stream of everyone else's announcements.
- The broker is role-blind. It doesn't know what "trading" or "billing" means — it just relays events. Subscribers filter locally.
- The `ui` container runs nginx; one nginx process does double duty (see next section). It serves the SPA *and* routes `/api/trading/*` and `/api/billing/*` to the right BFF upstream, with passive health checks for transparent BFF failover.
- Each backend hosts three gRPC services: `EchoService` (business RPCs), `DrainService` (control), and the standard `grpc.health.v1.Health`. BFFs of the same role open a `Health/Watch` stream against each.
- The BFFs do *not* host a gRPC server. Their only gRPC role is **client**: they call the broker (announce / subscribe) and the backends (echo / drain / health-watch).
- An additional **Consul** layer probes both backends and BFFs as a parallel observability plane — see [CONSUL.md](CONSUL.md).

## What serves the UI to the browser?

A single container called **`ui`**, running **nginx 1.27-alpine** built from `web-client/Dockerfile`. There is *no* separate "frontend server" or static-file server — nginx wears both hats.

### How the image is built

`web-client/Dockerfile` is a two-stage build:

```dockerfile
# Stage 1: build the React bundle
FROM node:20-alpine AS build
WORKDIR /src
COPY web-client/package.json web-client/package-lock.json* ./
RUN --mount=type=cache,target=/root/.npm npm install
COPY web-client ./
RUN npm run build           # → /src/dist/index.html + JS + CSS

# Stage 2: nginx serving that bundle + reverse-proxying /api
FROM nginx:1.27-alpine
COPY web-client/nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /src/dist /usr/share/nginx/html
```

So the final image contains the *built* SPA (already compiled, minified, tree-shaken by Vite) plus one nginx config. Vite is not running at request time — there's no Node.js process in the `ui` container at all.

### Two jobs, one nginx config

`web-client/nginx.conf` has two responsibilities in the same `server { listen 80; }` block:

```nginx
server {
    listen 80;
    server_name _;
    root /usr/share/nginx/html;       # the built SPA lives here
    index index.html;

    # Job 1: serve the SPA. Any path that doesn't start with /api/.
    # try_files falls back to /index.html so client-side routing works.
    location / {
        try_files $uri $uri/ /index.html;
    }

    # Job 2: reverse-proxy /api/trading/* to the trading BFFs upstream.
    location /api/trading/ {
        rewrite ^/api/trading/(.*)$ /api/$1 break;
        proxy_pass http://trading_bff;
        proxy_buffering off;          # SSE needs this off
        proxy_read_timeout 1h;        # SSE streams live a long time
        ...
    }

    location /api/billing/ { /* same shape, different upstream */ }
}
```

### Tracing a request from a browser click

When you click **"Send 1 Echo"** in the UI's trading tab, two HTTP requests reach the `ui` container:

```
1. GET http://localhost:5173/
   ── browser asks for the page
   ── nginx: location /  → serves /usr/share/nginx/html/index.html
   ── browser parses, requests bundled JS, gets them from the same nginx
   ── React app runs in the browser

2. POST http://localhost:5173/api/trading/echo
   ── triggered by the React app's onClick
   ── nginx: location /api/trading/  → rewrites path to /api/echo
   ── proxy_pass http://trading_bff (upstream block: bff-t-1, bff-t-2)
   ── nginx picks a healthy BFF (round-robin / failover)
   ── BFF calls a backend over gRPC, returns the response
   ── nginx forwards the response back to the browser
```

The browser never directly talks to a BFF — every `/api/*` request goes through nginx in the `ui` container, which is also where the SPA itself came from. This is why "the UI" and "nginx front door" are the same container.

### Why one container instead of two

We could have run `nginx` and a separate "static SPA server" as two containers. Combining them is standard practice for SPA + reverse-proxy setups because:

- The SPA bundle is just files — nginx serves files trivially fast.
- Same `:80` port for SPA *and* API means the browser sees no CORS concerns.
- One fewer container; one fewer hop.

In production you might split them (CDN for static assets + dedicated reverse-proxy / API-gateway), but for this POC the integration is straightforward.

## Module layout

```
proto/             echo.proto + registry.proto + lifecycle.proto
lifecycle-broker/  pub/sub broker (gRPC fan-out hub)
grpc-server/       backend (one image, many containers via env-var config)
bff/               Micronaut HTTP + gRPC client + broker subscriber
web-client/        Vite + React + TypeScript UI (also serves nginx front door)
```

## The control-plane proto

Two services do all the orchestration. From `proto/schemas/lifecycle.proto`:

```protobuf
service LifecycleBroker {
  rpc Announce (NodeInfo) returns (Ack);
  rpc Withdraw (NodeRef) returns (Ack);
  rpc Subscribe (SubscribeRequest) returns (stream LifecycleEvent);
}

message NodeInfo {
  NodeKind kind = 1;       // BFF or SERVER
  string role = 2;         // "trading", "billing", ...
  string node_id = 3;      // unique within the cluster
  string address = 4;      // host:port — how to reach this node
}
```

From `proto/schemas/registry.proto`:

```protobuf
service DrainService {     // hosted by each backend
  rpc RequestDrain (DrainRequest) returns (DrainResponse);
}
```

And the standard `grpc.health.v1.Health` from `io.grpc:grpc-services`.

## How a backend announces itself

`grpc-server/src/main/java/com/example/poc/server/ServerApp.java`:

1. **Backend boots.**
2. It opens its gRPC server on `SERVER_PORT` (9101 by default) hosting `EchoServiceImpl`, `DrainServiceImpl`, and the health service (initial status `SERVING`).
3. `Server.start()` returns once the listener is bound. **Only then** does it spawn the `broker-announcer` thread.
4. `BrokerAnnouncer.announceWithRetry()` opens a one-shot channel to the broker, calls `Announce(kind=SERVER, role=$ROLE, node_id=$SERVER_ID, address=$ADVERTISED_HOST:$PORT)`, and retries with exponential backoff until accepted. This covers the broker not being up yet.
5. On `SIGTERM`, the shutdown hook fires a best-effort `Withdraw(node_id)` before the JVM exits.

The backend doesn't subscribe to anything — it only publishes.

## How a BFF subscribes

`bff/src/main/java/com/example/poc/bff/broker/BrokerSubscriber.java` is a `@Context` (eager-singleton) bean that owns a long-lived `Subscribe(stream)` RPC against the broker.

1. **Reconnect loop.** Stream errors don't terminate the subscriber — they trigger an exponential-backoff reconnect (max 5 s).
2. **Snapshot on connect.** The broker sends `SNAPSHOT_ITEM` × N (one per currently-known node) followed by `SNAPSHOT_END`, then live `JOINED` / `LEFT` events.
3. **Two-stage application of each event:**
   - **Always:** record in `KnownNodesRegistry` (the passive ledger that drives the UI's cluster-topology panel).
   - **Then for SERVER events whose role matches ours:** call `ServerRegistry.register(id, host, port)`. This is what actually turns a broker event into a routable target — opens a `ManagedChannel`, starts a `HealthWatcher`.
4. **`Context.ROOT.withCancellation()`** — the subscribe stream is rooted in `Context.ROOT` so it's not a child of any inbound RPC context (we don't have one here, but the pattern stays consistent with `HealthWatcher`).

A separate bean `BrokerAnnouncer` announces the BFF itself (kind=BFF) on startup and withdraws on shutdown — belt-and-braces with the broker's auto-LEFT-on-subscription-disconnect (see below).

## How the broker handles the pub/sub

`lifecycle-broker/src/main/java/com/example/poc/broker/LifecycleBrokerServiceImpl.java` is tiny:

- A `ConcurrentHashMap<String, NodeInfo>` holds the current cluster view.
- A `ConcurrentHashMap<String, Subscription>` holds active subscribers (keyed by a UUID per subscribe call).
- `Announce` inserts/replaces in the map and broadcasts `JOINED` to all subscribers.
- `Withdraw` removes from the map and broadcasts `LEFT`.
- `Subscribe` sends the snapshot synchronously, then registers the subscriber's `ServerCallStreamObserver` for future broadcasts. It installs `setOnCancelHandler` / `setOnCloseHandler` so when the stream dies, the broker:
  1. Removes the subscriber from `subs`.
  2. If the subscriber identified itself in `SubscribeRequest.subscriber_id` (BFFs do — backends don't subscribe), the broker also `remove`s that node from the cluster view and emits a `LEFT` for it.

That last point is how peer-BFF death is detected without any active health checking: when the BFF process dies, its subscription stream breaks; the broker fires the cancel handler; everyone else learns the BFF is gone.

## How health checking works

Every BFF opens one `grpc.health.v1.Health/Watch` stream per same-role backend, kept open for the backend's entire lifetime. Implementation: `bff/src/main/java/com/example/poc/bff/registry/HealthWatcher.java`.

The `Watch` RPC is server-streaming. The client sends a single request (with the service name to watch — empty string `""` means overall server health), and the server pushes status updates whenever they change:

- `SERVING` → BFF marks the entry **HEALTHY** (router will pick it).
- `NOT_SERVING` → BFF marks the entry **DRAINING** (router will skip it).
- `UNKNOWN` / `SERVICE_UNKNOWN` → **UNHEALTHY** (router will skip).

The channel is built with **keepalive tuned for fast failure detection**:

| Setting | Value | Why |
|---|---|---|
| `keepAliveTime` | 2 s | Send a PING every 2 s when otherwise idle |
| `keepAliveTimeout` | 1 s | If no PONG within 1 s, declare the channel broken |
| `keepAliveWithoutCalls` | true | Send pings even with no active RPC — otherwise an idle channel stays "open" for minutes after the peer dies |
| `permitKeepAliveTime` (server side) | 1 s | Allow client pings as often as every 1 s; must be ≤ the client's `keepAliveTime` |
| `permitKeepAliveWithoutCalls` (server side) | true | Otherwise the server sends `GOAWAY: enhance_your_calm` |

### Three subtle gotchas

1. **`Context.ROOT`, not `Context.current()`** — earlier versions opened the watch in `Context.current().withCancellation()` from inside the inbound `RegisterServer` handler. That made the watcher a child of the inbound call's context, and it was cancelled the moment the call completed. Symptom: every watch errored within milliseconds of registration with `CANCELLED: io.grpc.Context was cancelled without error`.
2. **Entry identity, not just id.** When a backend container is killed and quickly restarted, the BFF's stale watcher's `onError` fires *after* the new entry has been inserted. Removing by id wipes out the healthy newcomer. `ServerRegistry.healthFailed(entry)` uses `entries.compute` with an identity check (`current == watched`) — only removes if the entry is still the one this watcher was watching.
3. **JVM DNS cache.** When a container restarts, it gets a new IP. The JVM caches DNS lookups by default for ~30 s. `-Dnetworkaddress.cache.ttl=2` doesn't work in most JDKs (it's a *security* property, not a system property). The reliable `-D` override is `-Dsun.net.inetaddr.ttl=0`, set via `JAVA_TOOL_OPTIONS` in the BFF and broker Dockerfiles. We use `0` rather than a small positive value because rapid kill+restart cycles (e.g. the test suite) can hit the cache window within seconds.

## Kill detection — the full path

What happens, end-to-end, when you run `./bin/c kill server-t-1`:

```
T0           Docker sends SIGKILL → backend process disappears.
             The TCP connections from both trading BFFs are now
             half-open; the kernels don't know yet.

T0 + ~1 s    Each BFF's channel keepalive timer fires. PING sent;
             1-second PONG timeout starts.

T0 + ~2 s    PONG doesn't arrive. The channel transitions to
             TRANSIENT_FAILURE; the Health.Watch stream fires
             onError("UNAVAILABLE: Network closed for unknown reason").

T0 + ~2 s    BFF-side cleanup:
               1. ServerRegistry.healthFailed(entry) — identity
                  check, then removes the entry.
               2. Marks DEAD, emits statusChanged(DEAD) + serverRemoved
                  on the SSE stream.
               3. RoundRobinRouter now skips this server entirely.
               4. Best-effort: sends Withdraw(server-t-1) to the
                  broker so peer BFFs converge fast.

T0 + ~2 s    Broker propagates the Withdraw as LEFT to all subscribers.
             Other BFFs receive LEFT, remove from KnownNodesRegistry.
             The UI's "cluster topology" panel loses the row.

Total: ~1–2 s from kill to "no traffic going there, everyone agrees".
```

If a BFF dies (rather than a backend), the path is different:

```
T0           Docker sends SIGKILL → BFF process disappears.

T0 + ε       The BFF's Subscribe stream to the broker dies. Broker's
             setOnCancelHandler fires, removes the BFF from its map,
             broadcasts LEFT(bff-t-1) to remaining subscribers.

T0 + ε       Next UI request arrives at nginx; nginx tries bff-t-1
             first (still in its upstream list), hits ECONNREFUSED or
             a connect timeout (we set proxy_connect_timeout=2s),
             retries on bff-t-2 — request succeeds.

T0 + ε       nginx marks bff-t-1 unavailable for fail_timeout=5s.
             Subsequent requests skip it entirely. After 5s nginx
             retries; if still dead, the cycle repeats.

User-visible: one request takes ~2s extra. The rest are normal.
```

## Load balancing

`RoundRobinRouter` is deliberately simple — round-robin lives in the BFF rather than relying on gRPC-java's built-in policies, so we have explicit control over which entries to skip and so the UI's view matches the routing decisions.

```java
public Optional<ServerEntry> pickHealthy() {
    List<ServerEntry> healthy = registry.snapshot().stream()
            .filter(e -> e.status() == ServerStatus.HEALTHY)
            .toList();
    if (healthy.isEmpty()) return Optional.empty();
    int idx = Math.floorMod(cursor.getAndIncrement(), healthy.size());
    return Optional.of(healthy.get(idx));
}
```

If a request arrives and no server is healthy, the controller returns HTTP 503.

## nginx as front door

`web-client/nginx.conf` has two upstreams and two `location` blocks:

```nginx
upstream trading_bff {
    server bff-t-1:8080 max_fails=1 fail_timeout=5s;
    server bff-t-2:8080 max_fails=1 fail_timeout=5s;
    # server bff-t-3:8080 max_fails=1 fail_timeout=5s;   # uncomment + reload to add
    # (no `keepalive N` — see below)
}

upstream billing_bff { ... }

location /api/trading/ {
    rewrite ^/api/trading/(.*)$ /api/$1 break;
    proxy_pass http://trading_bff;
    proxy_connect_timeout       2s;
    proxy_next_upstream         error timeout http_502 http_503 http_504;
    proxy_next_upstream_tries   3;
    proxy_buffering off;            # SSE
    proxy_read_timeout 1h;          # SSE
}
```

Key choices:

- **Path-prefix routing.** Requests come in as `/api/trading/echo`; nginx strips the `/trading` prefix before forwarding. The BFF stays at its native `/api/echo` regardless of role.
- **Passive health checks** (`max_fails=1 fail_timeout=5s`). nginx OSS doesn't actively poll upstreams; instead it discovers failures on real requests and marks the upstream unavailable for 5 seconds after the first failure. With continuous UI traffic this gives ~ms-level failover; with idle traffic, the first request after a BFF dies pays a one-time `proxy_connect_timeout` penalty.
- **`proxy_buffering off` + long `proxy_read_timeout`** for the SSE stream.
- **No `keepalive N` on the upstream block.** With it, nginx biases new connections toward upstreams it already has warm connections to — which makes a newly-added BFF underused for a while. For the POC we prefer clean per-request round-robin over a few ms of saved TCP handshake.

### Adding upstreams to nginx live

OSS nginx insists every upstream hostname resolves at config-load time. `bff-t-3` doesn't exist on the Docker network until you `./bin/compose --profile extra up -d bff-t-3`, so listing it active in the upstream block at boot makes nginx refuse to start (`host not found in upstream`).

Production-realistic pattern: keep the line commented out, then edit + reload when the container comes online.

```bash
./bin/compose --profile extra up -d bff-t-3
./bin/c exec ui sed -i 's|# server bff-t-3|server bff-t-3|' /etc/nginx/conf.d/default.conf
./bin/c exec ui nginx -s reload
```

`nginx -s reload` is a zero-downtime config swap — the master spawns new workers with the new config; old workers finish their current connections then exit. In-flight requests aren't dropped. Note that traffic distribution takes a few seconds to even out as new connections drift onto the new worker generation.

Alternatives that avoid the edit-and-reload entirely:
- **Traefik** with Docker labels — new containers register themselves; the front door updates automatically.
- **nginx Plus** with `resolver` + `server ... resolve;` directives — DNS is consulted at request time, not config-load.
- **Envoy** or a service mesh — full dynamic discovery via xDS.

This POC uses OSS nginx + edit-and-reload because it's the most honest representation of what most teams actually run in production.

## Graceful drain — the full path

When you click "Drain" in the UI:

```
T0           UI calls POST /api/{role}/servers/server-t-1/drain.
             BFF returns 200 immediately, off-threads the drain RPC.

T0 + ε       BFF calls DrainServiceGrpc on server-t-1:
               RequestDrain(deadline_seconds: 3)

T0 + ε       Backend's DrainServiceImpl.requestDrain():
               1. health.setStatus("", NOT_SERVING).
               2. onNext(accepted=true) + onCompleted.
               3. Spawns a "drainer" thread.

T0 + ε       (Concurrently) Both BFFs' HealthWatchers receive
             onNext(NOT_SERVING). Each marks the entry DRAINING.
             SSE emits statusChanged(DRAINING). UI card pulses amber.
             RoundRobinRouter now skips this server.

T0..T0+3s    The drainer thread calls server.shutdown() (refuses new
             RPCs, lets in-flight unary calls finish), then sleeps for
             the grace period (3 s).

T0 + 3 s     drainer calls server.shutdownNow() — this closes the
             Health.Watch streams that the BFFs have been holding open.
             Then System.exit(0).

T0 + 3 s     Both BFFs' HealthWatchers fire onError (RST_STREAM CANCEL).
             healthFailed removes the entry, emits statusChanged(DEAD)
             + serverRemoved, sends Withdraw to broker.
```

### Why a fixed grace and not `awaitTermination`?

`awaitTermination` waits for *all* in-flight RPCs to finish — including the long-lived `Health.Watch` streams that the BFFs hold open. Those streams only close when someone explicitly closes them. So `awaitTermination(10s)` always waits the full 10 seconds even with zero real work in flight. A fixed grace + `shutdownNow()` is simpler and gives the demo a predictable, visible cadence (~3 s of DRAINING then card disappears).

## Live UI updates — Server-Sent Events

The BFF exposes a single SSE endpoint at `/api/events` (which the UI hits as `/api/{role}/events`). Each subscriber receives a fresh snapshot followed by live events:

| Event | Payload | When fired |
|---|---|---|
| `snapshot` | `{ servers: [...], nodes: [...] }` | Once at subscribe time. `servers` is the routable same-role pool; `nodes` is the full cluster topology. |
| `serverAdded` | `{ server: {...} }` | Same-role backend joined the routable pool |
| `serverRemoved` | `{ id: "..." }` | Same-role backend left the routable pool |
| `statusChanged` | `{ id, status }` | Health watch saw a transition (HEALTHY / DRAINING / DEAD / UNHEALTHY) |
| `requestRouted` | `{ id, handledAtMs, message }` | An `/api/echo` or `/api/compute` was successfully routed |
| `nodeJoined` | `{ node: {...} }` | Any node anywhere (other roles, BFFs) joined the cluster |
| `nodeLeft` | `{ id: "..." }` | Any node anywhere left the cluster |

Implementation in `bff/src/main/java/com/example/poc/bff/registry/EventBus.java`:

```java
private final Sinks.Many<RegistryEvent> sink =
        Sinks.many().multicast().onBackpressureBuffer(1024, false);

public synchronized void emit(RegistryEvent event) {
    Sinks.EmitResult r = sink.tryEmitNext(event);
    if (r.isFailure() && r != Sinks.EmitResult.FAIL_ZERO_SUBSCRIBER) {
        LOG.debug("Dropped event {} ({})", event.type(), r);
    }
}
```

Specific choices:

- **`onBackpressureBuffer(1024)` not `directBestEffort()`** — `directBestEffort` dropped ~90% of events under burst load.
- **`tryEmitNext` not `emitNext`** — `emitNext` can throw an `OverflowException` that propagates out of the controller after the response is already on the wire.
- **`synchronized`** — eliminates `FAIL_NON_SERIALIZED` races between gRPC executor threads and HTTP request threads.

The UI's reducer (`web-client/src/lib/events.ts`) is idempotent — duplicate `serverAdded` / `nodeJoined` events are no-ops — so the slight buffer-replay behavior is harmless.

## Module-by-module summary

### `proto/`

- `echo.proto` — business RPCs (`Echo`, `Compute`, `GetServerInfo`).
- `registry.proto` — `DrainService.RequestDrain`.
- `lifecycle.proto` — `LifecycleBroker.Announce` / `Withdraw` / `Subscribe`.
- `grpc.health.v1.Health` comes from `io.grpc:grpc-services`.

### `lifecycle-broker/`

Plain grpc-java app. ~120 lines of business logic. Tracks announced nodes in a `ConcurrentHashMap`, holds subscriber streams, fans out `JOINED` / `LEFT` events. Uses `ServerCallStreamObserver`'s `setOnCancelHandler` / `setOnCloseHandler` to auto-emit `LEFT` when a subscriber's stream breaks.

### `grpc-server/`

Plain grpc-java app. Multi-instance via `SERVER_ID`, `SERVER_PORT`, `ROLE`, `BROKER_TARGET` env vars.

- `ServerApp.java` — builds gRPC server, mounts services, spawns broker-announcer thread, installs SIGTERM shutdown hook that flips health to `NOT_SERVING` + best-effort `Withdraw`.
- `EchoServiceImpl.java` — `Echo`, `Compute` (sleeps `workMs`), `GetServerInfo`.
- `DrainServiceImpl.java` — the drain logic described above.
- `BrokerAnnouncer.java` — exponential-backoff retry loop for `Announce`, plus `withdraw()` for the shutdown hook.
- `ConsulRegistrar.java` — registers with Consul on boot (see [CONSUL.md](CONSUL.md)).

### `bff/`

Micronaut HTTP-only app (no embedded gRPC server in this slice — the BFF only acts as a gRPC *client*).

Controllers:
- `EchoController`, `ComputeController` — route to a healthy entry, emit `requestRouted`.
- `ServersController` — `GET /api/servers` snapshot + `POST /api/servers/{id}/drain`.
- `EventsController` — SSE stream with snapshot-prefix.
- `KnownNodesController` — `GET /api/known-nodes` for the cluster-topology panel.
- `IdentityController` — `GET /api/identity` so the UI can show which BFF nginx landed it on.
- `InternalHealthController` — `GET /api/internal/health` for Consul probes.

Broker layer (`bff/.../broker/`):
- `BrokerAnnouncer` — `@Context` bean: announces this BFF on startup, withdraws on shutdown.
- `BrokerSubscriber` — `@Context` bean: long-lived Subscribe stream with reconnect; routes events into both registries.

Consul layer (`bff/.../consul/`):
- `ConsulRegistrar` — `@Context` bean: registers the BFF with Consul on startup, deregisters on shutdown.

Registry layer (`bff/.../registry/`):
- `ServerRegistry` — same-role routable pool. Always-replace `register`, identity-checking `healthFailed`, broker-Withdraw on death.
- `KnownNodesRegistry` — passive ledger of every node the broker has told us about, any role / kind.
- `ServerEntry`, `HealthWatcher`, `EventBus`, `RegistryEvent`, `ServerView`, `KnownNode`, `ServerStatus`.

Load balancer:
- `RoundRobinRouter` — snapshot-and-pick over HEALTHY entries.

### `web-client/`

Vite + React + TypeScript. No state management library — just hooks.

- `lib/events.ts` — `useRegistryStream(role)` hook (reopens SSE on role change).
- `lib/api.ts` — `fetch` wrappers, all role-prefixed.
- `lib/colors.ts` — deterministic per-server colors.
- `components/RoleTabs.tsx` — trading / billing switcher.
- `components/TopBar.tsx`, `DistributionBar.tsx`, `ServersPanel.tsx`, `RequestFirer.tsx` — the dashboard.
- `components/KnownNodesPanel.tsx` — cluster-topology view across all roles, with "routed" vs "recorded only" badges.

The same nginx container serves the built static bundle *and* routes `/api/{role}/*` to the matching BFF upstream. SSE-friendly settings: `proxy_buffering off`, `proxy_read_timeout 1h`.

---

Related: [CONSUL.md](CONSUL.md) for the parallel Consul layer · [OPERATIONS.md](OPERATIONS.md) for run / API / ports.
