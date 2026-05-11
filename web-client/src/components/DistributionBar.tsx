import type { ServerView } from "../lib/api";
import { serverColor } from "../lib/colors";

export function DistributionBar({ servers }: { servers: ServerView[] }) {
  const total = servers.reduce((acc, s) => acc + s.totalHandled, 0);
  if (total === 0) {
    return (
      <div className="dist">
        <div className="dist-bar dist-empty">No requests yet</div>
      </div>
    );
  }
  return (
    <div className="dist">
      <div className="dist-bar">
        {servers.map((s) => {
          const pct = (s.totalHandled / total) * 100;
          if (pct === 0) return null;
          return (
            <div
              key={s.id}
              className="dist-slice"
              style={{ width: `${pct}%`, background: serverColor(s.id) }}
              title={`${s.id}: ${s.totalHandled} (${pct.toFixed(1)}%)`}
            >
              {pct > 8 && <span className="dist-label">{s.id}</span>}
            </div>
          );
        })}
      </div>
      <div className="dist-legend">
        {servers.map((s) => (
          <span key={s.id} className="dist-legend-item">
            <span className="swatch" style={{ background: serverColor(s.id) }} />
            <span className="lbl">{s.id}</span>
            <span className="num">{s.totalHandled}</span>
          </span>
        ))}
      </div>
    </div>
  );
}
