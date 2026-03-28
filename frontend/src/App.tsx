import { useMemo, useRef, useState } from "react";

const API_BASE = "/api/v1";

type Role = "user" | "assistant" | "system";
type ChatItem = { id: string; role: Role; text: string };
type EventPayload = {
  type?: string;
  detail?: {
    answer?: string;
    summary?: string;
    message?: string;
    phase?: string;
    provider?: string;
    model?: string;
    reason?: string;
    status?: string;
  };
  status?: string;
};

export default function App() {
  const [sessionId, setSessionId] = useState<string | null>(null);
  const [runId, setRunId] = useState<string | null>(null);
  const [input, setInput] = useState("");
  const [busy, setBusy] = useState(false);
  const [messages, setMessages] = useState<ChatItem[]>([
    { id: "welcome", role: "system", text: "已连接 Nexus Agent。输入问题后点击发送。" },
  ]);
  const [lastEvent, setLastEvent] = useState<string>("—");
  const esRef = useRef<EventSource | null>(null);

  const canSend = useMemo(() => !busy && input.trim().length > 0, [busy, input]);

  const appendMessage = (role: Role, text: string) => {
    setMessages((prev) => [...prev, { id: `${Date.now()}-${Math.random()}`, role, text }]);
  };

  const ensureSession = async () => {
    if (sessionId) return sessionId;
    const res = await fetch(`${API_BASE}/sessions`, { method: "POST" });
    if (!res.ok) throw new Error(await res.text());
    const data = (await res.json()) as { sessionId: string };
    setSessionId(data.sessionId);
    appendMessage("system", `会话已创建：${data.sessionId}`);
    return data.sessionId;
  };

  const startSse = (newRunId: string) => {
    esRef.current?.close();
    setLastEvent(`订阅中 /runs/${newRunId}/events`);
    const es = new EventSource(`${API_BASE}/runs/${newRunId}/events`);
    esRef.current = es;

    es.addEventListener("run.started", (ev) => {
      const payload = JSON.parse((ev as MessageEvent).data) as EventPayload;
      const provider = payload.detail?.provider ?? "unknown";
      setLastEvent(`run.started (${provider})`);
    });

    es.addEventListener("reasoning.step", (ev) => {
      const payload = JSON.parse((ev as MessageEvent).data) as EventPayload;
      const phase = payload.detail?.phase ?? "STEP";
      const summary = payload.detail?.summary ?? "";
      setLastEvent(`reasoning.step ${phase}`);
      if (summary) appendMessage("system", `[${phase}] ${summary}`);
    });

    es.addEventListener("run.completed", (ev) => {
      const payload = JSON.parse((ev as MessageEvent).data) as EventPayload;
      const answer = payload.detail?.answer ?? "运行完成，但未返回 answer 字段。";
      appendMessage("assistant", answer);
      setLastEvent("run.completed");
      setBusy(false);
      es.close();
    });

    es.addEventListener("run.failed", (ev) => {
      const payload = JSON.parse((ev as MessageEvent).data) as EventPayload;
      const reason = payload.detail?.reason ?? "UNKNOWN";
      const message = payload.detail?.message ?? "运行失败";
      appendMessage("system", `运行失败(${reason})：${message}`);
      setLastEvent("run.failed");
      setBusy(false);
      es.close();
    });

    es.onerror = () => {
      setLastEvent("SSE 连接中断");
      setBusy(false);
      es.close();
    };
  };

  const sendPrompt = async () => {
    if (!canSend) return;
    const prompt = input.trim();
    setInput("");
    setBusy(true);
    appendMessage("user", prompt);

    try {
      const sid = await ensureSession();
      const res = await fetch(`${API_BASE}/sessions/${sid}/runs`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ prompt }),
      });
      if (!res.ok) {
        const err = await res.json().catch(() => ({}));
        throw new Error(JSON.stringify(err));
      }
      const data = (await res.json()) as { runId: string };
      setRunId(data.runId);
      startSse(data.runId);
    } catch (e) {
      appendMessage("system", `请求失败：${String(e)}`);
      setBusy(false);
    }
  };

  return (
    <div style={{ fontFamily: "system-ui", padding: 24, maxWidth: 900, margin: "0 auto" }}>
      <h1>Nexus Agent 聊天</h1>
      <p style={{ color: "#666", marginTop: -6 }}>
        当前页面已支持：输入问题 → 自动创建 run → 自动订阅 SSE → 渲染模型输出。
      </p>

      <div style={{ marginBottom: 12, fontSize: 13, color: "#444" }}>
        <div>
          <strong>sessionId</strong>: {sessionId ?? "未创建"}
        </div>
        <div>
          <strong>runId</strong>: {runId ?? "—"}
        </div>
        <div>
          <strong>lastEvent</strong>: {lastEvent}
        </div>
      </div>

      <div
        style={{
          border: "1px solid #ddd",
          borderRadius: 8,
          padding: 12,
          minHeight: 360,
          background: "#fafafa",
          marginBottom: 12,
          overflow: "auto",
        }}
      >
        {messages.map((m) => (
          <div
            key={m.id}
            style={{
              marginBottom: 10,
              display: "flex",
              justifyContent:
                m.role === "user" ? "flex-end" : "flex-start",
            }}
          >
            <div
              style={{
                maxWidth: "80%",
                whiteSpace: "pre-wrap",
                padding: "8px 10px",
                borderRadius: 8,
                background:
                  m.role === "user"
                    ? "#dff3ff"
                    : m.role === "assistant"
                    ? "#ecffe7"
                    : "#f1f1f1",
              }}
            >
              {m.text}
            </div>
          </div>
        ))}
      </div>

      <div style={{ display: "flex", gap: 8 }}>
        <input
          value={input}
          onChange={(e) => setInput(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === "Enter" && !e.shiftKey) {
              e.preventDefault();
              void sendPrompt();
            }
          }}
          placeholder="输入你的问题，例如：请总结今天的待办重点"
          style={{
            flex: 1,
            border: "1px solid #ccc",
            borderRadius: 8,
            padding: "10px 12px",
            fontSize: 14,
          }}
          disabled={busy}
        />
        <button
          type="button"
          onClick={() => void sendPrompt()}
          disabled={!canSend}
          style={{ minWidth: 90 }}
        >
          {busy ? "处理中..." : "发送"}
        </button>
      </div>
    </div>
  );
}
