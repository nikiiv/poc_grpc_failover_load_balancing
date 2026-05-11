import { useEffect, useState } from "react";
import { rpsFromWindow, useRegistryStream } from "./lib/events";
import { serverColor } from "./lib/colors";
import { ServersPanel } from "./components/ServersPanel";
import { TopBar } from "./components/TopBar";
import { DistributionBar } from "./components/DistributionBar";
import { RequestFirer } from "./components/RequestFirer";

export default function App() {
  const live = useRegistryStream();
  // Tick once a second so RPS and timeAgo() refresh even with no new events.
  const [, setTick] = useState(0);
  useEffect(() => {
    const t = window.setInterval(() => setTick((n) => n + 1), 1000);
    return () => window.clearInterval(t);
  }, []);

  const rps = rpsFromWindow(live.rpsWindow);
  const healthyCount = live.servers.filter((s) => s.status === "HEALTHY").length;

  return (
    <div className="app">
      <header>
        <h1>gRPC Failover &amp; Load Balancing</h1>
        <p className="subtitle">
          React → Micronaut BFF → N × gRPC backends · self-registration, Health.Watch, graceful drain
        </p>
      </header>

      <TopBar
        servers={live.servers}
        recentCount={live.recent.length}
        rps={rps}
        connected={live.connected}
      />

      <DistributionBar servers={live.servers} />

      <ServersPanel servers={live.servers} />

      <RequestFirer disabled={healthyCount === 0} />

      <section className="log">
        <h2>Recent requests</h2>
        {live.recent.length === 0 ? (
          <p className="empty">No requests yet — try <em>Send 1 Echo</em> or <em>Burst 20</em>.</p>
        ) : (
          <ul>
            {live.recent.map((r, i) => (
              <li key={`${r.handledAtMs}-${i}`}>
                <span className="chip" style={{ background: serverColor(r.serverId) + "22", color: serverColor(r.serverId) }}>
                  {r.serverId}
                </span>
                <span className="msg">{r.message}</span>
                <span className="ts">
                  {new Date(r.handledAtMs).toLocaleTimeString()}
                </span>
              </li>
            ))}
          </ul>
        )}
      </section>
    </div>
  );
}
