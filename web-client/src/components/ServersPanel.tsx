import { useState } from "react";
import type { ServerView } from "../lib/api";
import { drainServer } from "../lib/api";
import { serverColor } from "../lib/colors";

export function ServersPanel({ servers }: { servers: ServerView[] }) {
  return (
    <section className="servers">
      <h2>Servers ({servers.length})</h2>
      {servers.length === 0 ? (
        <p className="empty">No backends registered yet.</p>
      ) : (
        <ul className="server-cards">
          {servers.map((s) => (
            <ServerCard key={s.id} server={s} />
          ))}
        </ul>
      )}
    </section>
  );
}

function ServerCard({ server: s }: { server: ServerView }) {
  const [draining, setDraining] = useState(false);

  const canDrain = s.status === "HEALTHY";
  const color = serverColor(s.id);

  async function onDrain() {
    setDraining(true);
    try {
      await drainServer(s.id);
    } catch {
      setDraining(false);
    }
  }

  return (
    <li className={`server-card status-${s.status.toLowerCase()}`} style={{ borderLeftColor: color }}>
      <div className="row">
        <span className="server-id" style={{ color }}>{s.id}</span>
        <span className={`pill pill-${s.status.toLowerCase()}`}>{s.status}</span>
      </div>
      <div className="row meta">
        <span>{s.host}:{s.port}</span>
        <span>handled <b>{s.totalHandled}</b></span>
      </div>
      <div className="row meta">
        <span>in-flight <b>{s.inFlight}</b></span>
        <span>last seen {timeAgo(s.lastSeenMs)}</span>
      </div>
      <div className="row actions">
        <button
          className="btn-drain"
          onClick={onDrain}
          disabled={!canDrain || draining}
          title={canDrain ? "Stop routing new requests, finish in-flight, then exit" : "Only HEALTHY servers can be drained"}
        >
          {draining ? "Draining…" : "Drain"}
        </button>
      </div>
    </li>
  );
}

function timeAgo(ms: number): string {
  const secs = Math.max(0, (Date.now() - ms) / 1000);
  if (secs < 2) return "just now";
  if (secs < 60) return `${secs.toFixed(0)}s ago`;
  return `${(secs / 60).toFixed(0)}m ago`;
}
