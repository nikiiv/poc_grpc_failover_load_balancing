# gRPC failover & load balancing — POC

Demonstrates dynamic service discovery, gRPC health checking, kill detection, and graceful drain across a small four-tier stack:

**React/Vite UI → Micronaut BFF → N × Java gRPC backends**

Backends self-register with the BFF on startup. The BFF round-robins traffic across the healthy ones, watches each backend's `grpc.health.v1.Health` stream, and exposes live state to the UI over Server-Sent Events. A "Drain" button gracefully retires a backend; `kill -9` is detected via gRPC keepalive within ~1–2 seconds.

## Run

Needs **Docker ≥ 24** or **Podman ≥ 5**. Wrapper scripts auto-detect either.

```bash
./bin/compose up --build
```

Then open <http://localhost:5173>.

```bash
./bin/compose --profile extra up -d server-c   # spawn a 3rd backend live
./bin/c kill server-a                          # SIGKILL — abrupt
./bin/c stop server-b                          # SIGTERM — graceful drain via shutdown hook
./bin/compose down
```

## Layout

```
proto/         echo.proto + registry.proto
grpc-server/   plain grpc-java backend (one image, multiple containers)
bff/           Micronaut HTTP + embedded gRPC server (for registration)
web-client/    Vite + React + TypeScript UI
```

## API

| Method | Path | Purpose |
|---|---|---|
| `GET`  | `/api/servers` | List registered backends |
| `POST` | `/api/echo` | `{"message":"..."}` → routes to a healthy backend |
| `POST` | `/api/compute` | `{"workMs":300}` → slow request, useful for drain visuals |
| `POST` | `/api/servers/{id}/drain` | Gracefully drain a backend |
| `GET`  | `/api/events` | SSE stream — `snapshot`, `serverAdded`, `serverRemoved`, `statusChanged`, `requestRouted` |

## Native dev

JDK 17 and Node 20.

```bash
./gradlew start_bff           # :8080 HTTP, :7001 gRPC
./gradlew start_server_a      # :9101, registers with 127.0.0.1:7001
./gradlew start_server_b      # :9102
./gradlew start_ui            # Vite dev server :5173
```

## Notes

- BFF gRPC port is **7001**, not 7000 — macOS Control Center (AirPlay Receiver) binds 7000 by default.
- `kill -9` detection relies on gRPC channel keepalive (`2 s / 1 s`). Without it TCP can take minutes to surface a dead peer.
- The BFF's `Health.Watch` stream is opened in `Context.ROOT.withCancellation()` so it isn't a child of the inbound `RegisterServer` RPC context (which gets cancelled when registration completes).
- Drain uses fixed grace + `shutdownNow()` rather than `awaitTermination()` because the BFF's open `Health.Watch` stream counts as in-flight to grpc-java.
