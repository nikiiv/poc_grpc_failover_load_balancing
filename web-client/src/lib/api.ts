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

export async function sendEcho(message: string): Promise<EchoReply> {
  const res = await fetch("/api/echo", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ message }),
  });
  if (!res.ok) {
    throw new Error(`HTTP ${res.status}: ${await res.text()}`);
  }
  return (await res.json()) as EchoReply;
}

export async function sendCompute(workMs: number): Promise<ComputeReply> {
  const res = await fetch("/api/compute", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ workMs }),
  });
  if (!res.ok) {
    throw new Error(`HTTP ${res.status}: ${await res.text()}`);
  }
  return (await res.json()) as ComputeReply;
}

export async function drainServer(id: string): Promise<void> {
  const res = await fetch(`/api/servers/${encodeURIComponent(id)}/drain`, {
    method: "POST",
  });
  if (!res.ok) {
    throw new Error(`HTTP ${res.status}: ${await res.text()}`);
  }
}

export async function listServers(): Promise<ServerView[]> {
  const res = await fetch("/api/servers");
  if (!res.ok) throw new Error(`HTTP ${res.status}`);
  return (await res.json()) as ServerView[];
}
