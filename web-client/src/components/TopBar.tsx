import type { ServerView } from "../lib/api";

interface Props {
  servers: ServerView[];
  recentCount: number;
  rps: number;
  connected: boolean;
}

export function TopBar({ servers, recentCount: _recentCount, rps, connected }: Props) {
  const healthy = servers.filter((s) => s.status === "HEALTHY").length;
  const total = servers.length;
  const totalHandled = servers.reduce((acc, s) => acc + s.totalHandled, 0);

  return (
    <div className="topbar">
      <Stat label="Healthy" value={`${healthy} / ${total}`} accent={healthy > 0 ? "ok" : "bad"} />
      <Stat label="Total handled" value={totalHandled.toLocaleString()} />
      <Stat label="Requests / sec" value={rps.toFixed(1)} />
      <div className="topbar-spacer" />
      <div className={`live ${connected ? "live-ok" : "live-bad"}`}>
        <span className="dot">●</span> {connected ? "live" : "reconnecting…"}
      </div>
    </div>
  );
}

function Stat({
  label,
  value,
  accent,
}: {
  label: string;
  value: string;
  accent?: "ok" | "bad";
}) {
  return (
    <div className={`stat ${accent ? `stat-${accent}` : ""}`}>
      <div className="stat-label">{label}</div>
      <div className="stat-value">{value}</div>
    </div>
  );
}
