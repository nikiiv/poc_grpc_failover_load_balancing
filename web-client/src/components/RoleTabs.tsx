import type { Role } from "../lib/api";

const ROLES: Role[] = ["trading", "billing"];

interface Props {
  current: Role;
  bffNodeId: string | null;
  onChange: (r: Role) => void;
}

export function RoleTabs({ current, bffNodeId, onChange }: Props) {
  return (
    <div className="role-tabs">
      <div className="role-tabs-buttons">
        {ROLES.map((r) => (
          <button
            key={r}
            className={`role-tab ${r === current ? "role-tab-active" : ""}`}
            onClick={() => onChange(r)}
          >
            {r}
          </button>
        ))}
      </div>
      {bffNodeId && (
        <div className="role-tab-bff">
          connected via <b>{bffNodeId}</b>
        </div>
      )}
    </div>
  );
}
