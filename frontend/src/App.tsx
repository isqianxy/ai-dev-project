import { useCallback, useRef, useState } from "react";

const API_BASE = "/api/v1";

type LogLine = { t: string; raw: string };

export default function App() {
  const [sessionId, setSessionId] = useState<string | null>(null);
  const [runId, setRunId] = useState<string | null>(null);
  const [logs, setLogs] = useState<LogLine[]>([]);
  const [busy, setBusy] = useState(false);
  const esRef = useRef<EventSource | null>(null);

  const appendLog = useCallback((raw: string) => {
    setLogs((prev) => [...prev, { t: new Date().toISOString(), raw }]);
  }, []);

  const createSession = async () => {
    setBusy(true);
    try {
      const res = await fetch(`${API_BASE}/sessions`, { method: "POST" });
      if (!res.ok) throw new Error(await res.text());
      const data = (await res.json()) as { sessionId: string };
      setSessionId(data.sessionId);
      setRunId(null);
      setLogs([]);
      appendLog(`会话已创建: ${data.sessionId}`);
    } catch (e) {
      appendLog(`错误: ${String(e)}`);
    } finally {
      setBusy(false);
    }
  };

  const submitRun = async () => {
    if (!sessionId) {
      appendLog("请先创建会话");
      return;
    }
    setBusy(true);
    try {
      const res = await fetch(`${API_BASE}/sessions/${sessionId}/runs`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: "{}",
      });
      if (!res.ok) {
        const err = await res.json().catch(() => ({}));
        throw new Error(JSON.stringify(err));
      }
      const data = (await res.json()) as { runId: string };
      setRunId(data.runId);
      appendLog(`Run 已创建: ${data.runId}`);
    } catch (e) {
      appendLog(`错误: ${String(e)}`);
    } finally {
      setBusy(false);
    }
  };

  const subscribeSse = () => {
    if (!runId) {
      appendLog("请先提交任务以获取 runId");
      return;
    }
    esRef.current?.close();
    setLogs([]);
    appendLog(`订阅 SSE: /runs/${runId}/events`);
    const es = new EventSource(`${API_BASE}/runs/${runId}/events`);
    esRef.current = es;

    const onAny = (ev: MessageEvent) => {
      appendLog(`[event] ${(ev as MessageEvent & { type?: string }).type ?? "message"} ${ev.data}`);
    };

    es.addEventListener("run.started", onAny);
    es.addEventListener("reasoning.step", onAny);
    es.addEventListener("run.completed", onAny);
    es.onerror = () => {
      appendLog("SSE 连接错误或已结束");
      es.close();
    };
  };

  return (
    <div style={{ fontFamily: "system-ui", padding: 24, maxWidth: 960 }}>
      <h1>Nexus Agent — M1 骨架</h1>
      <p style={{ color: "#444" }}>
        本地先启动后端（端口 8080），再执行 <code>npm run dev</code>。本页通过 Vite 代理访问{" "}
        <code>/api</code>。
      </p>
      <div style={{ display: "flex", gap: 8, flexWrap: "wrap", marginBottom: 16 }}>
        <button type="button" disabled={busy} onClick={createSession}>
          1. 创建会话
        </button>
        <button type="button" disabled={busy || !sessionId} onClick={submitRun}>
          2. 提交空任务
        </button>
        <button type="button" disabled={!runId} onClick={subscribeSse}>
          3. 订阅 SSE（Mock 事件）
        </button>
      </div>
      <div style={{ fontSize: 14 }}>
        <div>
          <strong>sessionId</strong>: {sessionId ?? "—"}
        </div>
        <div>
          <strong>runId</strong>: {runId ?? "—"}
        </div>
      </div>
      <pre
        style={{
          marginTop: 16,
          background: "#111",
          color: "#e0e0e0",
          padding: 12,
          minHeight: 200,
          overflow: "auto",
        }}
      >
        {logs.map((l) => (
          <div key={l.t + l.raw}>
            [{l.t}] {l.raw}
          </div>
        ))}
      </pre>
    </div>
  );
}
