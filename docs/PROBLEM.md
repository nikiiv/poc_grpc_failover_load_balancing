# Problem and proposed solution

← back to [README](../README.md)

## The problem in plain language

Modern applications don't run on a single server anymore. They run on **clusters** of identical worker processes — sometimes two, sometimes hundreds — that share traffic between them. This is how you make a system **fast** (more workers handle more requests in parallel) and **reliable** (if one worker dies, the others keep going).

But this only works if "the others keep going" actually happens. In practice four things go wrong constantly:

1. **Servers crash without warning.** Hardware failure, out-of-memory, a poison-pill request. The traffic gateway has to *notice* and stop sending requests to a dead server within seconds — not minutes.
2. **Servers get added or removed all the time.** Cloud autoscalers add instances at peak hours, deployments roll out new versions, operators retire old hardware. The gateway has to *learn about* these changes automatically.
3. **Servers need to be retired gracefully.** Before a deploy or a maintenance window, you want to drain a server — let it finish what it's already doing, but stop sending new work to it. Done wrong, customers see errors during routine deploys.
4. **The gateway itself can fail.** A single load-balancer / BFF / API-gateway is the single point of failure for everything behind it. Production deployments run multiple of these and survive any one going down.

Most systems handle these manually: someone updates a config file, restarts the gateway, hopes for the best. That doesn't scale, it's error-prone, and at 3 AM it's how outages happen.

## What this POC demonstrates

A self-healing system for gRPC services that runs **redundant** at every layer:

- **Multiple BFFs per service.** Two trading BFFs and two billing BFFs run side-by-side. The front door (nginx) round-robins requests across them; any one BFF can die and the user sees no error.
- **Self-announcing servers and BFFs.** A new container starts up and tells a central pub/sub broker "I'm here." Everyone else who cares finds out immediately. No DNS records to maintain, no config file to edit, no service-discovery cluster (Consul, etcd) to operate.
- **Crash detection is fast.** When a worker is killed abruptly (`kill -9`, hardware failure, network partition), the system notices within ~1 second using gRPC's built-in keepalive mechanism, removes it from the routing pool, and instantly starts sending its share of traffic to the survivors.
- **Graceful drain is one click.** An operator clicks a "Drain" button next to a server. The server flips a flag that says "I'm leaving"; the BFFs stop sending new requests to it; the requests *already in flight* finish normally; then the server exits cleanly. Zero customer-visible errors during the drain.
- **Role-aware buckets.** Distinct services (trading, billing, …) run as independent buckets. Each BFF only routes to servers of its own role, but the broker tells everyone about everyone — so any UI can show the full cluster topology while the routing stays cleanly partitioned.
- **A live dashboard shows everything as it happens.** Server cards appear when new workers start, pulse amber while draining, flash red and slide out when they die. A "cluster topology" panel shows the entire system across all roles, with badges marking which ones are *routed to* by the current BFF and which are *recorded only*.

## What this enables

| Capability | What it means in production |
|---|---|
| **Zero-downtime deploys** | Roll new versions out one worker at a time, draining each before stopping it. Customers never see an error from the rollout. |
| **Auto-recovery** | A crashed worker is removed from the routing pool within seconds; a restarted worker rejoins automatically. No human intervention needed for the routine cases. |
| **Horizontal scaling** | Add capacity by starting more worker containers. They register themselves. The BFFs start using them immediately. |
| **BFF redundancy** | Multiple identical BFFs serve every role. A single BFF crash is invisible to users — nginx fails over with one retry. |
| **Service partitioning** | Trading and billing run as separate buckets that can fail, scale, and deploy independently. Killing all the billing servers doesn't affect a single trading request. |
| **Operational visibility** | Real-time view of which workers are alive, how loaded each is, where traffic is going. Useful for both routine monitoring and incident response. |
| **A foundation for blue-green / canary** | The drain primitive is what powers gradual rollouts — direct a few percent of traffic to a new version and increase if it looks healthy. |

## What this is *not*

- **Not production-ready.** This is a proof of concept. It deliberately omits TLS, authentication, persistent storage, and other things you'd want before deploying for real. The patterns it demonstrates, however, are the same ones used in production gRPC systems at scale.
- **Not a replacement for Kubernetes.** Kubernetes does much of this for you out of the box. This POC is useful when you want to understand how the underlying mechanics work, or when you need fine-grained control over the routing behavior that off-the-shelf platforms don't expose.

## What you'll see in the demo

A two-to-three-minute live walk-through:

1. **Start.** Eleven containers come up: the broker, Consul, two trading BFFs, two trading backends, two billing BFFs, two billing backends, and the `ui` container (nginx — serves the React bundle *and* fronts both BFF pools at `/api/{role}/*`). The UI shows two green cards for the trading bucket. Click "Send 1 Echo" — the response shows which server handled it and which BFF nginx routed through.
2. **Burst.** Click "Burst 20 × Compute" — twenty requests fire at once and the distribution bar fills in proportionally. Round-robin in action.
3. **Hard kill a server.** Run `./bin/c kill server-t-1`. Within a second, the red flash; the card slides out. New requests now all go to the survivor. **No errors are visible to the user.**
4. **Add capacity.** Run `./bin/compose --profile extra up -d server-t-3`. A new green card slides in. Traffic immediately starts routing there.
5. **Graceful drain.** Click "Drain" on a card. Amber pill, in-flight counter ticking down, then the card slides out calmly. Quite a different visual from the kill — and a different *narrative* about how the system was retired.
6. **Add a third BFF live.** Run `./bin/compose --profile extra up -d bff-t-3`, then `./bin/c exec ui sed -i 's|# server bff-t-3|server bff-t-3|' /etc/nginx/conf.d/default.conf && ./bin/c exec ui nginx -s reload`. Subsequent requests fan out across three BFFs — the BFF tier scales just like the backend tier did in step 4. (Why the sed+reload? See [ARCHITECTURE.md § Adding upstreams to nginx live](ARCHITECTURE.md#adding-upstreams-to-nginx-live).)
7. **Kill a BFF.** Run `./bin/c kill bff-t-1`. Continue clicking buttons in the UI — every request still succeeds. nginx transparently routes through `bff-t-2` or `bff-t-3`. The first request after the kill pays a one-time 2-second connect-timeout penalty; everything else is instant.
8. **Show the second bucket.** Switch to the **billing** tab. Different servers (`server-b-1`, `server-b-2`), routed through different BFFs (`bff-b-1`, `bff-b-2`). Notice the "cluster topology" panel: the billing BFF knows about every trading node too — just doesn't route to them.

> **Tip:** `./bin/demo` runs all of this for you (interactive, pauses between steps). `./bin/demo --auto` is the non-interactive smoke variant. Both tear down at the end.

---

Next: [ARCHITECTURE.md](ARCHITECTURE.md) — how the internals work.
