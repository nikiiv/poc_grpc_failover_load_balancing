export type Role = "trading" | "billing";

export interface EchoReply {
  message: string;
  serverId: string;
  handledAtMs: number;
}

export interface ComputeReply {
  serverId: string;
  elapsedMs: number;
}

export interface ServerView {
  id: string;
  host: string;
  port: number;
  status: "HEALTHY" | "UNHEALTHY" | "DRAINING" | "DEAD";
  inFlight: number;
  totalHandled: number;
  registeredAtMs: number;
  lastSeenMs: number;
}

export interface KnownNode {
  nodeId: string;
  kind: "BFF" | "SERVER";
  role: string;
  address: string;
  firstSeenMs: number;
  lastSeenMs: number;
}

export interface Identity {
  nodeId: string;
  role: string;
}

function apiBase(role: Role): string {
  return `/api/${role}`;
}

export async function sendEcho(role: Role, message: string): Promise<EchoReply> {
  const res = await fetch(`${apiBase(role)}/echo`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ message }),
  });
  if (!res.ok) throw new Error(`HTTP ${res.status}: ${await res.text()}`);
  return (await res.json()) as EchoReply;
}

export async function sendCompute(role: Role, workMs: number): Promise<ComputeReply> {
  const res = await fetch(`${apiBase(role)}/compute`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ workMs }),
  });
  if (!res.ok) throw new Error(`HTTP ${res.status}: ${await res.text()}`);
  return (await res.json()) as ComputeReply;
}

export async function drainServer(role: Role, id: string): Promise<void> {
  const res = await fetch(`${apiBase(role)}/servers/${encodeURIComponent(id)}/drain`, {
    method: "POST",
  });
  if (!res.ok) throw new Error(`HTTP ${res.status}: ${await res.text()}`);
}

export async function getIdentity(role: Role): Promise<Identity> {
  const res = await fetch(`${apiBase(role)}/identity`);
  if (!res.ok) throw new Error(`HTTP ${res.status}`);
  return (await res.json()) as Identity;
}
