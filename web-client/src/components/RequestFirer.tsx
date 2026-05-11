import { useEffect, useRef, useState } from "react";
import { sendCompute, sendEcho } from "../lib/api";

const RATES = [0, 1, 4, 10] as const;

export function RequestFirer({ disabled }: { disabled: boolean }) {
  const [rps, setRps] = useState<(typeof RATES)[number]>(0);
  const [error, setError] = useState<string | null>(null);
  const timerRef = useRef<number | null>(null);

  useEffect(() => {
    if (timerRef.current) window.clearInterval(timerRef.current);
    if (rps === 0 || disabled) return;
    const periodMs = 1000 / rps;
    timerRef.current = window.setInterval(() => {
      sendCompute(300).catch((e) => setError(e instanceof Error ? e.message : String(e)));
    }, periodMs);
    return () => {
      if (timerRef.current) window.clearInterval(timerRef.current);
    };
  }, [rps, disabled]);

  async function fireOnce() {
    setError(null);
    try {
      await sendEcho("hello");
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
  }

  async function burst() {
    setError(null);
    const promises = [];
    for (let i = 0; i < 20; i++) {
      promises.push(sendCompute(300));
    }
    await Promise.allSettled(promises);
  }

  return (
    <section className="firer">
      <div className="firer-row">
        <button onClick={fireOnce} disabled={disabled}>Send 1 Echo</button>
        <button onClick={burst} disabled={disabled} className="btn-burst">
          Burst 20 ×Compute
        </button>
        <div className="rate-group">
          <span className="rate-label">Auto-fire</span>
          {RATES.map((r) => (
            <button
              key={r}
              className={`rate ${rps === r ? "rate-on" : ""}`}
              onClick={() => setRps(r)}
              disabled={disabled && r !== 0}
            >
              {r === 0 ? "off" : `${r}/s`}
            </button>
          ))}
        </div>
      </div>
      {error && <div className="error">{error}</div>}
    </section>
  );
}
