import type { KnownNode, Role } from "../lib/api";
import { serverColor } from "../lib/colors";

interface Props {
  currentRole: Role;
  nodes: KnownNode[];
}

export function KnownNodesPanel({ currentRole, nodes }: Props) {
  // Group by role, then split BFFs vs servers.
  const byRole = new Map<string, KnownNode[]>();
  for (const n of nodes) {
    if (!byRole.has(n.role)) byRole.set(n.role, []);
    byRole.get(n.role)!.push(n);
  }
  const roles = [...byRole.keys()].sort();

  if (nodes.length === 0) {
    return (
      <section className="known-nodes">
        <h2>Cluster topology (from broker)</h2>
        <p className="empty">No nodes yet.</p>
      </section>
    );
  }

  return (
    <section className="known-nodes">
      <h2>Cluster topology (from broker)</h2>
      <p className="hint">
        Every node the broker has told us about. Routing only happens to <b>{currentRole}</b>{" "}
        servers; other roles are recorded but ignored for routing.
      </p>
      <div className="role-blocks">
        {roles.map((role) => (
          <div key={role} className={`role-block ${role === currentRole ? "role-mine" : "role-other"}`}>
            <h3>
              <span className="role-name">{role}</span>
              {role === currentRole && <span className="role-tag">routed</span>}
              {role !== currentRole && <span className="role-tag muted">recorded only</span>}
            </h3>
            <NodeRows nodes={byRole.get(role)!.filter((n) => n.kind === "BFF")} />
            <NodeRows nodes={byRole.get(role)!.filter((n) => n.kind === "SERVER")} />
          </div>
        ))}
      </div>
    </section>
  );
}

function NodeRows({ nodes }: { nodes: KnownNode[] }) {
  if (nodes.length === 0) return null;
  return (
    <ul className="node-rows">
      {nodes.map((n) => {
        const isServer = n.kind === "SERVER";
        return (
          <li key={n.nodeId}>
            <span className="node-kind">{n.kind}</span>
            <span
              className="node-id"
              style={isServer ? { color: serverColor(n.nodeId) } : undefined}
            >
              {n.nodeId}
            </span>
            <span className="node-addr">{n.address}</span>
          </li>
        );
      })}
    </ul>
  );
}
