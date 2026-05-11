import { useEffect, useState } from "react";
import type { Role } from "./lib/api";
import { getIdentity } from "./lib/api";
import { rpsFromWindow, useRegistryStream } from "./lib/events";
import { serverColor } from "./lib/colors";
import { ServersPanel } from "./components/ServersPanel";
import { TopBar } from "./components/TopBar";
import { DistributionBar } from "./components/DistributionBar";
import { RequestFirer } from "./components/RequestFirer";
import { KnownNodesPanel } from "./components/KnownNodesPanel";
import { RoleTabs } from "./components/RoleTabs";

export default function App() {
  const [role, setRole] = useState<Role>("trading");
  const live = useRegistryStream(role);
  const [bffNodeId, setBffNodeId] = useState<string | null>(null);

  // Resolve which BFF we've actually landed on (nginx-side decision).
  useEffect(() => {
    setBffNodeId(null);
    getIdentity(role).then((id) => setBffNodeId(id.nodeId)).catch(() => setBffNodeId(null));
  }, [role]);

  // Tick once a second so RPS / timeAgo refresh even with no new events.
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
          Pub/sub broker · multi-BFF · role-aware routing
        </p>
      </header>

      <RoleTabs current={role} bffNodeId={bffNodeId} onChange={setRole} />

      <TopBar
        servers={live.servers}
        recentCount={live.recent.length}
        rps={rps}
        connected={live.connected}
      />

      <DistributionBar servers={live.servers} />

      <ServersPanel role={role} servers={live.servers} />

      <RequestFirer role={role} disabled={healthyCount === 0} />

      <KnownNodesPanel currentRole={role} nodes={live.knownNodes} />

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
