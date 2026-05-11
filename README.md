# gRPC failover & load balancing — POC

A demonstration of dynamic service discovery, gRPC health checking, abrupt-failure detection, and graceful drain across a small four-tier stack:

**React/Vite UI → Micronaut BFF (Backend-For-Frontend) → N × Java gRPC backends**

Runs under either **Docker** or **Podman** with auto-detection; the same image set works on Linux and macOS.

---

## Part 1 — Problem and proposed solution

### The problem in plain language

Modern applications don't run on a single server anymore. They run on **clusters** of identical worker processes — sometimes two, sometimes hundreds — that share traffic between them. This is how you make a system **fast** (more workers handle more requests in parallel) and **reliable** (if one worker dies, the others keep going).

But this only works if "the others keep going" actually happens. In practice three things go wrong constantly:

1. **Servers crash without warning.** Hardware failure, out-of-memory, a poison-pill request. The traffic gateway has to *notice* and stop sending requests to a dead server within seconds — not minutes.
2. **Servers get added or removed all the time.** Cloud autoscalers add instances at peak hours, deployments roll out new versions, operators retire old hardware. The gateway has to *learn about* these changes automatically.
3. **Servers need to be retired gracefully.** Before a deploy or a maintenance window, you want to drain a server — let it finish what it's already doing, but stop sending new work to it. Done wrong, customers see errors during routine deploys.

Many systems handle these manually: someone updates a config file, restarts the gateway, hopes for the best. That doesn't scale, it's error-prone, and at 3 AM it's how outages happen.

### What this POC demonstrates

A self-healing load balancer for gRPC services. Specifically:

- **Servers announce themselves automatically.** A new worker container starts up and registers with the gateway over a gRPC call. No DNS records to maintain, no config file to edit, no service-discovery cluster (Consul, etcd) to operate.
- **Crash detection is fast.** When a worker is killed abruptly (`kill -9`, hardware failure, network partition), the gateway notices within ~1 second using gRPC's built-in keepalive mechanism, removes it from the routing pool, and instantly starts sending its share of traffic to the survivors.
- **Graceful drain is one click.** An operator clicks a "Drain" button next to a server. The server flips a flag that says "I'm leaving"; the gateway stops sending new requests to it; the requests *already in flight* finish normally; then the server exits cleanly. Zero customer-visible errors during the drain.
- **A live dashboard shows everything as it happens.** Server cards appear when new workers start, pulse amber while draining, flash red and slide out when they die. A distribution bar tracks how traffic spreads across the pool. Total handled requests and requests-per-second update in real time.

### What this enables

| Capability | What it means in production |
|---|---|
| **Zero-downtime deploys** | Roll new versions out one worker at a time, draining each before stopping it. Customers never see an error from the rollout. |
| **Auto-recovery** | A crashed worker is removed from the routing pool within seconds; a restarted worker rejoins automatically. No human intervention needed for the routine cases. |
| **Horizontal scaling** | Add capacity by starting more worker containers. They register themselves. The gateway starts using them immediately. |
| **Operational visibility** | Real-time view of which workers are alive, how loaded each is, where traffic is going. Useful for both routine monitoring and incident response. |
| **A foundation for blue-green / canary** | The drain primitive is what powers gradual rollouts — direct a few percent of traffic to a new version and increase if it looks healthy. |

### What this is *not*

- **Not production-ready.** This is a proof of concept. It deliberately omits TLS, authentication, persistent storage, and many other things you'd want before deploying for real. The patterns it demonstrates, however, are the same ones used in production gRPC systems at scale.
- **Not a replacement for Kubernetes.** Kubernetes does much of this for you out of the box. This POC is useful when you want to understand how the underlying mechanics work, or when you need fine-grained control over the routing behavior that off-the-shelf platforms don't expose.

### What you'll see in the demo

A two-minute live walk-through:

1. **Start.** Two worker containers come up. The UI shows two green cards. Click "Send 1 Echo" — the response shows which worker handled it.
2. **Burst.** Click "Burst 20 × Compute" — twenty requests fire at once and the distribution bar fills in proportionally to the colors of each worker. Round-robin in action.
3. **Hard kill.** Run `./bin/c kill server-a` from a terminal. Within a second, the red flash; the card slides out. New requests now all go to the survivor. **No errors are visible to the user.**
4. **Add capacity.** Run `./bin/compose --profile extra up -d server-c`. A new green card slides in. Traffic immediately starts routing there.
5. **Graceful drain.** Click "Drain" on a card. Amber pill, in-flight counter ticking down, then the card slides out calmly. Quite a different visual from the kill — and a different *narrative* about how the system was retired.
6. **Restart.** `./bin/c start server-a` — the card reappears. Round-robin includes it again.

---

## Part 2 — Technical implementation

### Architecture overview

```
                      ┌──────────────────────────────┐
                      │  React + Vite UI (5173)       │
                      │  ── SSE  → /api/events        │
                      │  ── REST → /api/echo, /servers │
                      └────────────┬─────────────────┘
                                   │  HTTP/REST + SSE
                                   ▼
              ┌────────────────────────────────────────────┐
              │  Micronaut BFF                              │
              │  ┌────────────────────────────────────────┐ │
              │  │ HTTP server :8080                       │ │
              │  │   /api/echo, /compute, /servers,       │ │
              │  │   /servers/{id}/drain, /events (SSE)   │ │
              │  └────────────────────────────────────────┘ │
              │  ┌────────────────────────────────────────┐ │
              │  │ gRPC server :7001                       │ │
              │  │   poc.registry.RegistryService          │ │
              │  └────────────────────────────────────────┘ │
              │  ┌────────────────────────────────────────┐ │
              │  │ In-memory registry + round-robin LB +  │ │
              │  │ per-backend Health.Watch streams +     │ │
              │  │ EventBus (Reactor Sinks.Many)          │ │
              │  └────────────────────────────────────────┘ │
              └──────────┬──────────────────┬───────────────┘
                         │ gRPC :9101       │ gRPC :9101
                         ▼                  ▼
               ┌─────────────────┐  ┌─────────────────┐
               │  grpc-server    │  │  grpc-server    │
               │  (server-a)     │  │  (server-b)     │
               │  ┌─────────────┐│  │  ┌─────────────┐│
               │  │ EchoService ││  │  │ EchoService ││
               │  │ DrainService││  │  │ DrainService││
               │  │ Health      ││  │  │ Health      ││
               │  └─────────────┘│  │  └─────────────┘│
               └─────────────────┘  └─────────────────┘
```

Key shapes:

- The BFF **runs a gRPC server** (port 7001) in addition to its HTTP server (8080). Backends call this gRPC server to register themselves.
- Each backend **runs a gRPC server** (port 9101 inside its container) hosting three services: `EchoService` (business logic), `DrainService` (control), `grpc.health.v1.Health` (health).
- All UI updates flow through a single **SSE stream** (`/api/events`), so the UI never has to poll.

### Module layout

```
proto/         echo.proto + registry.proto (shared schemas)
grpc-server/   plain grpc-java backend (one image, many containers)
bff/           Micronaut HTTP + embedded gRPC server
web-client/    Vite + React + TypeScript UI
```

### Service registration — how a backend "announces" itself

The whole control plane is built on **two gRPC services** that we define in `proto/schemas/registry.proto`:

```protobuf
// Hosted by the BFF.
service RegistryService {
  rpc RegisterServer (RegisterRequest) returns (RegisterResponse);
}

// Hosted by each backend.
service DrainService {
  rpc RequestDrain (DrainRequest) returns (DrainResponse);
}
```

The flow when a backend starts up:

1. **Backend boots** (`grpc-server/src/main/java/com/example/poc/server/ServerApp.java`).
2. It opens its **own gRPC server** on `SERVER_PORT` (9101 by default), hosting three services:
   - `EchoServiceImpl` — the "business" RPCs (`Echo`, `Compute`, `GetServerInfo`).
   - `DrainServiceImpl` — receives drain requests from the BFF.
   - `HealthStatusManager.getHealthService()` — the standard `grpc.health.v1.Health` service, with initial status `SERVING`.
3. The backend's `Server.start()` returns once the listener is bound. **Only then** does it spawn the `bff-registrar` background thread.
4. `RegistrationClient.registerWithRetry()` opens a one-shot gRPC channel to `BFF_REGISTRY` (e.g. `bff:7001`), invokes `RegisterServer(server_id, host, port)` with a 3-second deadline, and retries with exponential backoff (250 ms → 5 s) until the BFF responds with `accepted=true`. This handles the case where the BFF isn't up yet at boot — the backend just keeps trying.
5. On the BFF side, `RegistryServiceImpl.registerServer(...)` forwards to `ServerRegistry.register(id, host, port)`.

The BFF's `ServerRegistry.register` is the **interesting part**, because it has to handle a subtle race:

```java
public ServerEntry register(String id, String host, int port) {
    ServerEntry[] previous = new ServerEntry[1];
    ServerEntry fresh = entries.compute(id, (k, existing) -> {
        if (existing != null) {
            previous[0] = existing;       // mark old entry for tear-down
        }
        ManagedChannel ch = NettyChannelBuilder.forAddress(host, port)
                .usePlaintext()
                .keepAliveTime(2, TimeUnit.SECONDS)
                .keepAliveTimeout(1, TimeUnit.SECONDS)
                .keepAliveWithoutCalls(true)
                .build();
        return new ServerEntry(id, host, port, ch);   // ALWAYS replace
    });

    if (previous[0] != null) {
        previous[0].cancelHealth();
        previous[0].channel().shutdownNow();
    }

    HealthWatcher watcher = new HealthWatcher(fresh, this);
    fresh.setHealthCancel(watcher::stop);
    watcher.start();
    events.emit(RegistryEvent.serverAdded(ServerView.of(fresh)));
    return fresh;
}
```

**Why "replace on register" not "ignore if exists"?** When a backend container is killed and restarted with the same `SERVER_ID`, the new container may register *before* the BFF's keepalive has noticed the old container is dead. The naïve "skip if id already present" version we tried first kept the BFF stuck on a stale channel pointing at a dead peer. Always-replace fixes this — the old channel and its watcher are torn down, the new one takes over.

The fresh channel is constructed with **keepalive tuned for fast failure detection**:

| Setting | Value | Why |
|---|---|---|
| `keepAliveTime` | 2 s | Send a PING every 2 s when the channel is otherwise idle |
| `keepAliveTimeout` | 1 s | If we don't get a PONG within 1 s, declare the channel broken |
| `keepAliveWithoutCalls` | true | Send pings even when no active RPC — otherwise an idle channel can stay "open" for minutes after the peer dies |

The server side (`grpc-server/src/main/java/com/example/poc/server/ServerApp.java`) configures the matching:

| Setting | Value | Why |
|---|---|---|
| `permitKeepAliveTime` | 1 s | Allow client pings as often as every 1 s — must be ≤ the client's `keepAliveTime` |
| `permitKeepAliveWithoutCalls` | true | Allow pings on idle connections — otherwise the server sends `GOAWAY: enhance_your_calm` and the client channel breaks |

If you forget either side of this, the symptoms are confusing: with no client keepalive, `kill -9` takes the TCP-default ~minutes to surface; with mismatched permits, the channel mysteriously closes shortly after opening.

### Health checking — how the BFF monitors each backend

The BFF opens **one streaming `grpc.health.v1.Health/Watch` RPC per backend**, kept open for the backend's entire lifetime. The implementation lives in `bff/src/main/java/com/example/poc/bff/registry/HealthWatcher.java`.

The `Watch` RPC is server-streaming: the client sends a single request (with the service name to watch — empty string `""` means overall server health), and the server pushes status updates whenever they change. From the spec:

- `SERVING` — backend is ready to handle traffic
- `NOT_SERVING` — backend is alive but doesn't want traffic (drain in progress)
- `UNKNOWN` / `SERVICE_UNKNOWN` — backend doesn't know

The BFF maps these to its richer `ServerStatus` enum:

```
SERVING       → HEALTHY     (router will pick this entry)
NOT_SERVING   → DRAINING    (router will skip it)
UNKNOWN, etc. → UNHEALTHY   (router will skip it)
```

#### A subtle gotcha: which gRPC context the Watch lives in

Inside `RegistryServiceImpl.registerServer(...)`, calling `Context.current()` returns the *inbound RegisterServer call's context*. If you create a child cancellable context from that and start the watch on it, the watch is implicitly cancelled the moment `RegisterServer` returns its response. This took an hour to figure out the first time — symptom: every health watch error within milliseconds of registration with `CANCELLED: io.grpc.Context was cancelled without error`.

Fix in `HealthWatcher.start()`:

```java
// Detach from any inbound-RPC context. Without this, the watcher's
// context becomes a child of the inbound call's context and is cancelled
// the moment that call completes.
Context.CancellableContext ctx = Context.ROOT.withCancellation();
ctx.run(() -> stub.watch(req, observer));
```

#### Another subtle gotcha: entry identity, not just id

The watcher's `onError` callback runs when the stream breaks. The naïve implementation calls `registry.markDeadAndRemove(id)`, which removes whatever's at that id. But there's a race:

1. `T0`: old container killed.
2. `T0 + 0.3 s`: new container restarted (same id, new IP), registers.
3. `T0 + 0.3 s`: BFF replaces the entry — old watcher cancelled, new watcher started.
4. `T0 + 1 s`: **old** watcher's onError finally fires (it was running on a different thread). If it removes by id, it removes the *new* (healthy!) entry.

Fix: the watcher carries the `ServerEntry` it was created for. `ServerRegistry.healthFailed(entry)` uses `entries.compute` with an identity check:

```java
entries.compute(watched.id(), (k, current) -> {
    if (current == watched) {     // identity, not equality
        removed[0] = true;
        return null;              // remove
    }
    return current;               // already replaced — leave it alone
});
```

Stale failures are logged and ignored.

#### One more subtle gotcha: JVM DNS cache

When a backend container is killed and a new one starts with the same hostname, it almost always gets a **different IP** on the container bridge network. The BFF needs to resolve the new IP — but Java caches DNS lookups by default for ~30 seconds.

You'd think `-Dnetworkaddress.cache.ttl=2` would fix it, and the documentation suggests it should — but in most JDK builds that property is read as a **security property** from `java.security`, not as a `-D` system property. The reliable `-D` override is `sun.net.inetaddr.ttl`.

In `bff/Dockerfile`:

```dockerfile
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0 -Dsun.net.inetaddr.ttl=2 -Dsun.net.inetaddr.negative.ttl=0"
```

Without this, after `kill -9` followed by a quick restart, the BFF's fresh `ManagedChannel` resolves to the dead IP for up to half a minute and the new backend never gets routed to.

### Kill detection — the full path

What happens, end-to-end, when you run `./bin/c kill server-a`:

```
T0           Docker sends SIGKILL → container process disappears.
             The TCP connection from BFF to server-a is now half-open;
             the BFF's kernel doesn't know yet.

T0 + ~1 s    BFF's gRPC channel keepalive timer fires. It sends a PING
             on the channel and starts a 1-second timeout for the PONG.

T0 + ~2 s    PONG doesn't arrive (peer is gone). The channel transitions
             to TRANSIENT_FAILURE; the Health.Watch stream fires
             StreamObserver.onError(t) with a status like
             "UNAVAILABLE: Network closed for unknown reason".

T0 + ~2 s    HealthWatcher.onError logs the error and calls
             registry.healthFailed(entry).

             ServerRegistry.healthFailed:
               1. entries.compute removes the entry (identity check passes).
               2. Marks the entry status DEAD.
               3. Closes the (already dead) channel cleanly.
               4. Emits two SSE events: statusChanged(DEAD) and serverRemoved.

T0 + ~2 s    UI receives both events almost simultaneously. The card
             flashes red (DEAD status), then slides out (serverRemoved).
             RoundRobinRouter, which only picks HEALTHY entries, now
             skips this server entirely. Subsequent requests all go to
             the surviving backends.
```

Total time from `kill` to "no traffic going to that backend": **~1–2 seconds**.

### Load balancing

`RoundRobinRouter` is deliberately simple — the round-robin lives in the BFF rather than relying on gRPC-java's built-in policies, so we have explicit control over which entries to skip and so the UI's view matches the routing decisions.

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

If a request arrives and *no* server is healthy, the controller returns HTTP 503.

### Graceful drain — the full path

When you click "Drain" in the UI:

```
T0           UI calls POST /api/servers/server-a/drain.
             BFF returns 200 immediately, off-threads the drain RPC.

T0 + ε       BFF calls DrainServiceGrpc on server-a:
               RequestDrain(deadline_seconds: 3)

T0 + ε       Backend's DrainServiceImpl.requestDrain():
               1. Calls health.setStatus("", NOT_SERVING).
               2. Replies onNext(accepted=true) + onCompleted.
               3. Spawns a "drainer" thread.

T0 + ε       (Concurrently) BFF's HealthWatcher receives an onNext
             with NOT_SERVING. Registry sets status to DRAINING.
             SSE emits statusChanged(DRAINING). UI card pulses amber.
             RoundRobinRouter now skips this server.

T0..T0+3s    The drainer thread on the backend calls server.shutdown()
             (refuses new RPCs, lets in-flight unary calls finish),
             then sleeps for the grace period (3 s). In-flight calls
             complete during this window.

T0 + 3 s     drainer calls server.shutdownNow() — this closes the
             Health.Watch stream that the BFF has been holding open.
             Then System.exit(0).

T0 + 3 s     BFF's HealthWatcher fires onError because the stream
             closed (RST_STREAM with CANCEL code). healthFailed
             removes the entry, emits statusChanged(DEAD) and
             serverRemoved. UI card slides out calmly.
```

#### Why a fixed grace period and not `awaitTermination`?

The natural pattern is `server.shutdown()` followed by `awaitTermination(deadlineSeconds)`. We don't use that, because:

`awaitTermination` waits for *all* in-flight RPCs to finish — including the long-lived `Health.Watch` stream that the BFF opened against this backend. That stream stays open until someone closes it explicitly. So `awaitTermination(10s)` always waits the full 10 seconds even if there's no actual unary work in flight.

A fixed grace + `shutdownNow()` is simpler and gives the demo a predictable, visible cadence (~3 s of DRAINING then card disappears).

### Live UI updates — Server-Sent Events

The BFF exposes a single SSE endpoint at `/api/events`. Subscribers receive:

| Event | Payload | When fired |
|---|---|---|
| `snapshot` | `{ servers: [...] }` | Sent once when a subscriber connects |
| `serverAdded` | `{ server: {...} }` | A backend registered (or replaced an existing entry) |
| `serverRemoved` | `{ id: "..." }` | Entry removed from the registry |
| `statusChanged` | `{ id, status }` | Health watch saw a transition (HEALTHY/DRAINING/DEAD/UNHEALTHY) |
| `requestRouted` | `{ id, handledAtMs, message }` | An `/api/echo` or `/api/compute` was successfully routed |

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

A few specific choices and why:

- **`onBackpressureBuffer(1024)` not `directBestEffort()`** — `directBestEffort` dropped ~90% of events under burst load because its per-subscriber buffer is tiny. The bigger buffer handles bursts; the side effect (the first subscriber sees a brief replay of pre-subscribe events) is benign because `EventsController` always emits a fresh `snapshot` at subscribe time and the UI's event-applier is idempotent.
- **`tryEmitNext` not `emitNext`** — `emitNext` with a failure handler can throw an `OverflowException`. That used to propagate out of `ComputeController.compute`, *after* the response body was already on the wire, and Micronaut logged warnings about not being able to forward the error. `tryEmitNext` never throws; we log non-OK results and move on.
- **`synchronized`** — Sinks.Many can return `FAIL_NON_SERIALIZED` when multiple producer threads race. The synchronized wrapper makes emission single-producer from Reactor's perspective.

On the UI side (`web-client/src/lib/events.ts`):

```ts
const es = new EventSource("/api/events");
es.onmessage = (ev) => {
    const evt = JSON.parse(ev.data) as RegistryEvent;
    setState((prev) => applyEvent(prev, evt));
};
```

The reducer in `applyEvent` is idempotent — `serverAdded` for an existing id is a no-op, `serverRemoved` for an unknown id is a no-op. This lets us tolerate the (rare) duplicate event without state corruption.

### Module-by-module summary

#### `proto/`

Two schema files. `echo.proto` defines the business RPCs (`Echo`, `Compute`, `GetServerInfo`). `registry.proto` defines the control-plane RPCs (`RegisterServer`, `RequestDrain`). The standard `grpc.health.v1.Health` service is pulled in via the `grpc-services` jar — we don't vendor its proto.

#### `grpc-server/`

Plain grpc-java application (no Micronaut — faster startup, leaner image). One source set, one image. Multiple instances run with different `SERVER_ID` and `SERVER_PORT` env vars. Key files:

- `ServerApp.java` — builds the gRPC server with `EchoServiceImpl`, `DrainServiceImpl`, and the health service. Spawns the registrar thread. Installs a JVM shutdown hook that flips health to `NOT_SERVING` on SIGTERM (so `docker stop` produces a graceful-ish shutdown).
- `EchoServiceImpl.java` — implements `Echo`, `Compute` (sleeps `workMs` to simulate slow work), and `GetServerInfo`.
- `DrainServiceImpl.java` — the drain logic described above.
- `RegistrationClient.java` — exponential-backoff retry loop that calls `RegisterServer` until accepted.

#### `bff/`

Micronaut application running both HTTP (8080) and gRPC (7001). The gRPC server is wired up via `GrpcServerLifecycle` (an `@Context`-scoped bean that builds and starts the gRPC server in `@PostConstruct`).

Controllers:

- `EchoController` — `POST /api/echo` → router picks healthy entry → calls `EchoService.Echo` → returns response + emits `requestRouted`.
- `ComputeController` — same shape but calls `EchoService.Compute` and emits a `requestRouted` event with `"compute(Nms)"` as the message.
- `ServersController` — `GET /api/servers` snapshot + `POST /api/servers/{id}/drain` issues the drain RPC asynchronously.
- `EventsController` — `GET /api/events` returns `Flux<Event<RegistryEvent>>` with `produces=TEXT_EVENT_STREAM`. Defers the inner publisher so each new subscriber gets a fresh snapshot before joining the live stream.

Registry layer (`bff/.../registry/`):

- `ServerRegistry` — `ConcurrentHashMap<String, ServerEntry>` with the always-replace `register` and identity-checking `healthFailed`.
- `ServerEntry` — mutable bookkeeping for one backend (id, host, port, status, in-flight counter, total handled, the `ManagedChannel`, and a cached `EchoServiceBlockingStub`).
- `HealthWatcher` — one per entry; opens and holds the `Health.Watch` stream in `Context.ROOT`.
- `RegistryServiceImpl` — gRPC service that backends call to register.
- `GrpcServerLifecycle` — starts/stops the BFF's own gRPC server.
- `EventBus` — Reactor `Sinks.Many` with the configuration discussed above.
- `ServerView`, `RegistryEvent`, `ServerStatus` — wire-format records / enums.

Load balancer:

- `RoundRobinRouter` — snapshot-and-pick over HEALTHY entries.

#### `web-client/`

Vite + React + TypeScript. No state-management library — just React hooks. Key files:

- `lib/events.ts` — `useRegistryStream()` hook owns the SSE connection and the reducer.
- `lib/api.ts` — thin `fetch` wrappers.
- `components/TopBar.tsx` — healthy count, RPS, total handled.
- `components/DistributionBar.tsx` — stacked horizontal bar of per-server totals.
- `components/ServersPanel.tsx` — server cards with the Drain button.
- `components/RequestFirer.tsx` — Send 1 / Burst 20 / Auto-fire toggle.

The nginx container that serves the built static bundle in compose mode also proxies `/api/*` (including the SSE stream) to the BFF — see `web-client/nginx.conf`. SSE-specific settings: `proxy_buffering off`, `proxy_read_timeout 1h`.

---

## Run

```bash
./bin/compose up --build
# open http://localhost:5173
```

The wrappers in `bin/c` and `bin/compose` auto-detect `docker` (if its daemon is reachable) or fall back to `podman`. Both Docker ≥ 24 and Podman ≥ 5 are supported.

Demo commands:

```bash
./bin/compose --profile extra up -d server-c    # spawn a third backend live
./bin/c kill server-a                            # SIGKILL — abrupt
./bin/c stop server-a                            # SIGTERM — fires the shutdown hook
./bin/c start server-a                           # restart a stopped container
./bin/compose down
```

On Mac under Podman, first time:

```bash
podman machine init --cpus 4 --memory 6144
podman machine start
```

## Native dev (faster iteration)

JDK 17 and Node 20.

```bash
./gradlew start_bff           # :8080 HTTP, :7001 gRPC
./gradlew start_server_a      # :9101, registers with 127.0.0.1:7001
./gradlew start_server_b      # :9102
./gradlew start_ui            # Vite dev server :5173
```

## API reference

| Method | Path | Body | Purpose |
|---|---|---|---|
| `GET` | `/api/servers` | — | Snapshot list of registered backends |
| `POST` | `/api/echo` | `{"message": "..."}` | Routes to a healthy backend via round-robin |
| `POST` | `/api/compute` | `{"workMs": 300}` | Slow request (sleeps `workMs` on the backend) |
| `POST` | `/api/servers/{id}/drain` | — | Tells a backend to drain gracefully (returns immediately) |
| `GET` | `/api/events` | — | SSE stream; see the event table above |

## Ports

| Service | Port | Where exposed |
|---|---|---|
| UI | 5173 | Host → nginx :80 in the `ui` container |
| BFF HTTP | 8080 | Host → BFF |
| BFF gRPC | 7001 | Host → BFF (used by backends for registration) |
| Backends gRPC | 9101 | Inside the compose network only |

Note: **7001, not 7000** — macOS Control Center / AirPlay Receiver binds 7000 by default.

## Things that aren't in scope

- TLS — everything is plaintext. 
- Persistent registrations — the registry is in-memory, so restarting the BFF empties it.
- Retry / backoff on the `EchoStub.echo` call — currently a single attempt; failure returns 502. No backoff
- Per-service health (`grpc.health.v1.Health` supports watching individual service names). We only use the overall `""` service watch.
