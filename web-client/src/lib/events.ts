import { useEffect, useState } from "react";
import type { ServerView } from "./api";

export type RegistryEvent =
  | { type: "snapshot"; servers: ServerView[] }
  | { type: "serverAdded"; server: ServerView }
  | { type: "serverRemoved"; id: string }
  | { type: "statusChanged"; id: string; status: ServerView["status"] }
  | { type: "requestRouted"; id: string; handledAtMs: number; message: string };

export interface RequestEntry {
  serverId: string;
  message: string;
  handledAtMs: number;
}

export interface LiveState {
  servers: ServerView[];
  recent: RequestEntry[];
  connected: boolean;
  /** Rolling window of request timestamps (ms epoch) for RPS calculation. */
  rpsWindow: number[];
}

const INITIAL: LiveState = { servers: [], recent: [], connected: false, rpsWindow: [] };

const RPS_WINDOW_MS = 5_000;

export function useRegistryStream(): LiveState {
  const [state, setState] = useState<LiveState>(INITIAL);

  useEffect(() => {
    const es = new EventSource("/api/events");

    es.onopen = () => setState((s) => ({ ...s, connected: true }));
    es.onerror = () => setState((s) => ({ ...s, connected: false }));

    es.onmessage = (ev) => {
      let evt: RegistryEvent;
      try {
        evt = JSON.parse(ev.data) as RegistryEvent;
      } catch {
        return;
      }
      setState((prev) => applyEvent(prev, evt));
    };

    return () => es.close();
  }, []);

  return state;
}

function applyEvent(prev: LiveState, evt: RegistryEvent): LiveState {
  switch (evt.type) {
    case "snapshot":
      return { ...prev, servers: evt.servers };
    case "serverAdded":
      if (prev.servers.some((s) => s.id === evt.server.id)) return prev;
      return {
        ...prev,
        servers: [...prev.servers, evt.server].sort((a, b) => a.id.localeCompare(b.id)),
      };
    case "serverRemoved":
      return { ...prev, servers: prev.servers.filter((s) => s.id !== evt.id) };
    case "statusChanged":
      return {
        ...prev,
        servers: prev.servers.map((s) =>
          s.id === evt.id ? { ...s, status: evt.status } : s
        ),
      };
    case "requestRouted": {
      const now = Date.now();
      const cutoff = now - RPS_WINDOW_MS;
      const rpsWindow = [...prev.rpsWindow, now].filter((t) => t >= cutoff);
      return {
        ...prev,
        servers: prev.servers.map((s) =>
          s.id === evt.id ? { ...s, totalHandled: s.totalHandled + 1, lastSeenMs: evt.handledAtMs } : s
        ),
        recent: [
          { serverId: evt.id, message: evt.message, handledAtMs: evt.handledAtMs },
          ...prev.recent,
        ].slice(0, 50),
        rpsWindow,
      };
    }
  }
}

export function rpsFromWindow(window: number[]): number {
  if (window.length === 0) return 0;
  const now = Date.now();
  const recent = window.filter((t) => t >= now - RPS_WINDOW_MS);
  return (recent.length * 1000) / RPS_WINDOW_MS;
}
