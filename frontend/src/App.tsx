import { useCallback, useEffect, useMemo, useRef, useState } from "react";

const API_BASE = "/api/v1";
const EVENT_LOG_MAX = 500;

type Role = "user" | "assistant" | "system";
type ChatItem = { id: string; role: Role; text: string };

/** 与后端 ReasoningEngineService.eventPayload 一致 */
type SseEnvelope = {
  traceId?: string;
  type?: string;
  runId?: string;
  sessionId?: string;
  stepId?: string;
  detail?: Record<string, unknown>;
  status?: string;
};

type ToolInfo = {
  name: string;
  description: string;
  parameterType: string;
  riskLevel: string;
};

type LogEntry = { id: string; ts: string; event: string; traceId?: string; summary: string; raw: string };
type PendingApproval = { approvalId: string; action: string; toolName: string };

function formatTime() {
  return new Date().toLocaleTimeString("zh-CN", { hour12: false });
}

function summarizePayload(event: string, payload: SseEnvelope): string {
  const d = payload.detail;
  switch (event) {
    case "rag.retrieved":
      return `provider=${String(d?.provider ?? "")} hits=${String(d?.hitCount ?? "")} sources=${JSON.stringify(d?.sources ?? [])}`;
    case "tool.invoked":
      return `${String(d?.toolName ?? "")} args=${abbreviate(String(d?.argumentsJson ?? ""), 120)}`;
    case "tool.result":
      return `${String(d?.toolName ?? "")} ok=${String(d?.success ?? "")} out=${abbreviate(String(d?.output ?? ""), 80)} err=${d?.error ?? ""}`;
    case "approval.requested":
      return `approvalId=${String(d?.approvalId ?? "")} action=${String(d?.action ?? "")} tool=${String(d?.toolName ?? "")}`;
    case "reasoning.step":
      return `${String(d?.phase ?? "")} ${abbreviate(String(d?.summary ?? ""), 100)}`;
    case "run.started":
      return `provider=${String(d?.provider ?? "")}`;
    case "run.completed":
      return abbreviate(String(d?.answer ?? payload.status ?? ""), 120);
    case "run.failed":
      return `${String(d?.reason ?? "")}: ${abbreviate(String(d?.message ?? ""), 100)}`;
    default:
      return abbreviate(JSON.stringify(d ?? {}), 200);
  }
}

function abbreviate(s: string, max: number) {
  if (!s || s.length <= max) return s;
  return s.slice(0, max) + "…";
}

export default function App() {
  const [sessionId, setSessionId] = useState<string | null>(null);
  const [runId, setRunId] = useState<string | null>(null);
  const [traceId, setTraceId] = useState<string | null>(null);
  const [input, setInput] = useState("");
  const [busy, setBusy] = useState(false);
  const [messages, setMessages] = useState<ChatItem[]>([
    { id: "welcome", role: "system", text: "已连接 Nexus Agent。左侧聊天，右侧为 RAG / 工具 / MCP / SSE 调试面板。" },
  ]);
  const [lastEvent, setLastEvent] = useState<string>("—");
  const [tools, setTools] = useState<ToolInfo[]>([]);
  const [toolsError, setToolsError] = useState<string | null>(null);
  const [toolsLoading, setToolsLoading] = useState(false);
  const [invokeName, setInvokeName] = useState("");
  const [invokeArgs, setInvokeArgs] = useState("{}");
  const [invokeBusy, setInvokeBusy] = useState(false);
  const [invokeResult, setInvokeResult] = useState<string | null>(null);
  const [eventLog, setEventLog] = useState<LogEntry[]>([]);
  const [logFilter, setLogFilter] = useState("");
  const [pendingApproval, setPendingApproval] = useState<PendingApproval | null>(null);
  const [approvalBusy, setApprovalBusy] = useState(false);
  const esRef = useRef<EventSource | null>(null);

  const canSend = useMemo(() => !busy && input.trim().length > 0, [busy, input]);

  const pushLog = useCallback((event: string, rawJson: string, payload: SseEnvelope) => {
    const tid = payload.traceId;
    if (tid) setTraceId(tid);
    setEventLog((prev) => {
      const next: LogEntry[] = [
        ...prev,
        {
          id: `${Date.now()}-${Math.random()}`,
          ts: formatTime(),
          event,
          traceId: tid,
          summary: summarizePayload(event, payload),
          raw: rawJson,
        },
      ];
      return next.length > EVENT_LOG_MAX ? next.slice(-EVENT_LOG_MAX) : next;
    });
  }, []);

  const appendMessage = (role: Role, text: string) => {
    setMessages((prev) => [...prev, { id: `${Date.now()}-${Math.random()}`, role, text }]);
  };

  const fetchTools = useCallback(async () => {
    setToolsLoading(true);
    setToolsError(null);
    try {
      const res = await fetch(`${API_BASE}/tools`);
      if (!res.ok) throw new Error(await res.text());
      const list = (await res.json()) as ToolInfo[];
      setTools(Array.isArray(list) ? list : []);
      if (Array.isArray(list) && list.length > 0) {
        setInvokeName((prev) => (prev.trim() ? prev : list[0].name));
      }
    } catch (e) {
      setToolsError(String(e));
      setTools([]);
    } finally {
      setToolsLoading(false);
    }
  }, []);

  useEffect(() => {
    void fetchTools();
  }, [fetchTools]);

  const ensureSession = async () => {
    if (sessionId) return sessionId;
    const res = await fetch(`${API_BASE}/sessions`, { method: "POST" });
    if (!res.ok) throw new Error(await res.text());
    const data = (await res.json()) as { sessionId: string };
    setSessionId(data.sessionId);
    appendMessage("system", `会话已创建：${data.sessionId}`);
    return data.sessionId;
  };

  const wireSseEvent = (es: EventSource, eventName: string, handler: (payload: SseEnvelope, raw: string) => void) => {
    es.addEventListener(eventName, (ev) => {
      const raw = (ev as MessageEvent).data as string;
      try {
        const payload = JSON.parse(raw) as SseEnvelope;
        handler(payload, raw);
      } catch {
        handler({}, raw);
      }
    });
  };

  const startSse = (newRunId: string) => {
    esRef.current?.close();
    setLastEvent(`订阅中 /runs/${newRunId}/events`);
    setTraceId(null);
    const es = new EventSource(`${API_BASE}/runs/${newRunId}/events`);
    esRef.current = es;

    const onAny = (name: string, payload: SseEnvelope, raw: string) => {
      pushLog(name, raw, payload);
      setLastEvent(name);
    };

    wireSseEvent(es, "run.started", (p, r) => onAny("run.started", p, r));
    wireSseEvent(es, "reasoning.step", (p, r) => {
      onAny("reasoning.step", p, r);
      const summary = p.detail?.summary;
      if (typeof summary === "string" && summary) {
        appendMessage("system", `[${String(p.detail?.phase ?? "STEP")}] ${summary}`);
      }
    });
    wireSseEvent(es, "rag.retrieved", (p, r) => onAny("rag.retrieved", p, r));
    wireSseEvent(es, "approval.requested", (p, r) => {
      onAny("approval.requested", p, r);
      const aid = String(p.detail?.approvalId ?? "");
      const action = String(p.detail?.action ?? "");
      const toolName = String(p.detail?.toolName ?? "");
      if (aid) {
        setPendingApproval({ approvalId: aid, action, toolName });
      }
      appendMessage("system", `需要审批：tool=${toolName || "unknown"} approvalId=${aid}`);
    });

    wireSseEvent(es, "run.completed", (p, r) => {
      onAny("run.completed", p, r);
      const answer =
        typeof p.detail?.answer === "string"
          ? p.detail.answer
          : "运行完成，但未返回 answer 字段。";
      appendMessage("assistant", answer);
      setBusy(false);
      es.close();
    });

    wireSseEvent(es, "run.failed", (p, r) => {
      onAny("run.failed", p, r);
      const reason = String(p.detail?.reason ?? "UNKNOWN");
      const message = String(p.detail?.message ?? "运行失败");
      appendMessage("system", `运行失败(${reason})：${message}`);
      if (reason !== "APPROVAL_REQUIRED") {
        setPendingApproval(null);
      }
      setBusy(false);
      es.close();
    });

    es.onerror = () => {
      setLastEvent("SSE 连接中断");
      setBusy(false);
      es.close();
    };
  };

  const resolveApproval = async (decision: "APPROVED" | "REJECTED") => {
    if (!pendingApproval) return;
    setApprovalBusy(true);
    try {
      const res = await fetch(`${API_BASE}/approvals/${encodeURIComponent(pendingApproval.approvalId)}/resolve`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ decision, resolvedBy: "frontend" }),
      });
      if (!res.ok) {
        const err = await res.text();
        throw new Error(err || `HTTP ${res.status}`);
      }
      if (decision === "APPROVED") {
        appendMessage("system", `审批已通过：${pendingApproval.toolName || pendingApproval.action}`);
        if (runId) {
          appendMessage("system", `开始重试 run=${runId}`);
          setBusy(true);
          startSse(runId);
        }
      } else {
        appendMessage("system", `审批已拒绝：${pendingApproval.toolName || pendingApproval.action}`);
      }
      setPendingApproval(null);
    } catch (e) {
      appendMessage("system", `审批提交失败：${String(e)}`);
    } finally {
      setApprovalBusy(false);
    }
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

  const doInvokeTool = async () => {
    const name = invokeName.trim();
    if (!name) {
      setInvokeResult("请填写工具名");
      return;
    }
    setInvokeBusy(true);
    setInvokeResult(null);
    try {
      let args = invokeArgs.trim();
      if (!args) args = "{}";
      JSON.parse(args);
      const res = await fetch(`${API_BASE}/tools/${encodeURIComponent(name)}/invoke`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ argumentsJson: args }),
      });
      const body = await res.text();
      setInvokeResult(`${res.status} ${res.statusText}\n${body}`);
    } catch (e) {
      setInvokeResult(`调用异常：${String(e)}`);
    } finally {
      setInvokeBusy(false);
    }
  };

  const filteredLog = useMemo(() => {
    const q = logFilter.trim().toLowerCase();
    if (!q) return eventLog;
    return eventLog.filter(
      (e) =>
        e.event.toLowerCase().includes(q) ||
        e.summary.toLowerCase().includes(q) ||
        e.raw.toLowerCase().includes(q) ||
        (e.traceId && e.traceId.toLowerCase().includes(q))
    );
  }, [eventLog, logFilter]);

  const clearLog = () => setEventLog([]);

  const copyLog = async () => {
    const text = filteredLog.map((e) => `[${e.ts}] ${e.event} ${e.traceId ?? ""}\n${e.raw}`).join("\n\n");
    try {
      await navigator.clipboard.writeText(text);
      appendMessage("system", "事件日志已复制到剪贴板");
    } catch {
      appendMessage("system", "复制失败，请手动选择日志区域");
    }
  };

  return (
    <div style={{ fontFamily: "system-ui, sans-serif", padding: 20, maxWidth: 1280, margin: "0 auto" }}>
      <h1 style={{ marginBottom: 4 }}>Nexus Agent 调试台</h1>
      <p style={{ color: "#555", marginTop: 0, fontSize: 14 }}>
        聊天联调 DeepSeek · SSE 展示 <strong>RAG</strong> / 审批 · 右侧可拉工具列表并直接 invoke。
      </p>

      <div
        style={{
          display: "flex",
          flexWrap: "wrap",
          gap: 16,
          alignItems: "flex-start",
        }}
      >
        {/* 左侧：聊天 */}
        <div style={{ flex: "1 1 420px", minWidth: 280, maxWidth: "100%" }}>
          <div style={{ marginBottom: 10, fontSize: 13, color: "#333", lineHeight: 1.6 }}>
            <div>
              <strong>sessionId</strong>: {sessionId ?? "未创建"}
            </div>
            <div>
              <strong>runId</strong>: {runId ?? "—"}
            </div>
            <div>
              <strong>traceId</strong>: {traceId ?? "—"}
            </div>
            <div>
              <strong>lastEvent</strong>: {lastEvent}
            </div>
          </div>
          {pendingApproval && (
            <div
              style={{
                marginBottom: 12,
                border: "1px solid #e6b800",
                background: "#fff9e6",
                borderRadius: 8,
                padding: 10,
                fontSize: 13,
              }}
            >
              <div style={{ marginBottom: 6 }}>
                <strong>待审批</strong>：{pendingApproval.toolName || pendingApproval.action}
              </div>
              <div style={{ marginBottom: 8, color: "#444" }}>approvalId: {pendingApproval.approvalId}</div>
              <div style={{ display: "flex", gap: 8 }}>
                <button type="button" onClick={() => void resolveApproval("APPROVED")} disabled={approvalBusy}>
                  {approvalBusy ? "提交中…" : "批准并重试"}
                </button>
                <button type="button" onClick={() => void resolveApproval("REJECTED")} disabled={approvalBusy}>
                  {approvalBusy ? "提交中…" : "拒绝"}
                </button>
              </div>
            </div>
          )}

          <div
            style={{
              border: "1px solid #ddd",
              borderRadius: 8,
              padding: 12,
              minHeight: 380,
              background: "#fafafa",
              marginBottom: 12,
              overflow: "auto",
              maxHeight: 520,
            }}
          >
            {messages.map((m) => (
              <div
                key={m.id}
                style={{
                  marginBottom: 10,
                  display: "flex",
                  justifyContent: m.role === "user" ? "flex-end" : "flex-start",
                }}
              >
                <div
                  style={{
                    maxWidth: "90%",
                    whiteSpace: "pre-wrap",
                    padding: "8px 10px",
                    borderRadius: 8,
                    fontSize: 14,
                    background:
                      m.role === "user" ? "#dff3ff" : m.role === "assistant" ? "#ecffe7" : "#eee",
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
              placeholder="例如：先用 list_tools 看有哪些工具，再问菜谱（需 MCP 已启用）"
              style={{
                flex: 1,
                border: "1px solid #ccc",
                borderRadius: 8,
                padding: "10px 12px",
                fontSize: 14,
              }}
              disabled={busy}
            />
            <button type="button" onClick={() => void sendPrompt()} disabled={!canSend} style={{ minWidth: 88 }}>
              {busy ? "处理中…" : "发送"}
            </button>
          </div>
        </div>

        {/* 右侧：调试 */}
        <div
          style={{
            flex: "1 1 380px",
            minWidth: 280,
            maxWidth: "100%",
            border: "1px solid #c5d4e8",
            borderRadius: 8,
            padding: 12,
            background: "#f6f9fc",
            position: "sticky",
            top: 12,
            maxHeight: "calc(100vh - 40px)",
            overflow: "hidden",
            display: "flex",
            flexDirection: "column",
            gap: 12,
          }}
        >
          <section>
            <div style={{ display: "flex", alignItems: "center", gap: 8, marginBottom: 8 }}>
              <strong style={{ fontSize: 14 }}>工具清单（含 MCP）</strong>
              <button type="button" onClick={() => void fetchTools()} disabled={toolsLoading} style={{ fontSize: 12 }}>
                {toolsLoading ? "加载中…" : "刷新"}
              </button>
            </div>
            {toolsError && <div style={{ color: "#b00020", fontSize: 12 }}>{toolsError}</div>}
            <div
              style={{
                maxHeight: 200,
                overflow: "auto",
                fontSize: 12,
                background: "#fff",
                border: "1px solid #ddd",
                borderRadius: 6,
                padding: 8,
              }}
            >
              {tools.length === 0 && !toolsLoading ? (
                <span style={{ color: "#888" }}>暂无工具（检查后端与 MCP stdio 配置）</span>
              ) : (
                <ul style={{ margin: 0, paddingLeft: 18 }}>
                  {tools.map((t) => (
                    <li key={t.name} style={{ marginBottom: 6 }}>
                      <code style={{ background: "#eef", padding: "0 4px" }}>{t.name}</code>
                      <span style={{ color: "#666" }}> [{t.riskLevel}]</span>
                      <div style={{ color: "#444" }}>{abbreviate(t.description, 80)}</div>
                    </li>
                  ))}
                </ul>
              )}
            </div>
          </section>

          <section>
            <strong style={{ fontSize: 14 }}>直接调用工具（HTTP 调试）</strong>
            <div style={{ marginTop: 8, display: "flex", flexDirection: "column", gap: 6 }}>
              <input
                value={invokeName}
                onChange={(e) => setInvokeName(e.target.value)}
                placeholder="工具名，如 mcp.xxx 或 echo"
                style={{ padding: 8, fontSize: 13, borderRadius: 6, border: "1px solid #ccc" }}
              />
              <textarea
                value={invokeArgs}
                onChange={(e) => setInvokeArgs(e.target.value)}
                placeholder='参数 JSON，如 {"text":"hi"}'
                rows={4}
                style={{ fontFamily: "monospace", fontSize: 12, borderRadius: 6, border: "1px solid #ccc", padding: 8 }}
              />
              <button type="button" onClick={() => void doInvokeTool()} disabled={invokeBusy} style={{ alignSelf: "flex-start" }}>
                {invokeBusy ? "调用中…" : "POST /tools/{name}/invoke"}
              </button>
              {invokeResult && (
                <pre
                  style={{
                    margin: 0,
                    whiteSpace: "pre-wrap",
                    fontSize: 11,
                    background: "#fff",
                    border: "1px solid #ddd",
                    borderRadius: 6,
                    padding: 8,
                    maxHeight: 160,
                    overflow: "auto",
                  }}
                >
                  {invokeResult}
                </pre>
              )}
            </div>
          </section>

          <section style={{ flex: 1, display: "flex", flexDirection: "column", minHeight: 200 }}>
            <div style={{ display: "flex", alignItems: "center", gap: 8, marginBottom: 6, flexWrap: "wrap" }}>
              <strong style={{ fontSize: 14 }}>SSE 事件流</strong>
              <input
                value={logFilter}
                onChange={(e) => setLogFilter(e.target.value)}
                placeholder="过滤关键字"
                style={{ flex: 1, minWidth: 120, padding: 4, fontSize: 12 }}
              />
              <button type="button" onClick={clearLog} style={{ fontSize: 12 }}>
                清空
              </button>
              <button type="button" onClick={() => void copyLog()} style={{ fontSize: 12 }}>
                复制
              </button>
            </div>
            <div
              style={{
                flex: 1,
                overflow: "auto",
                background: "#1e1e1e",
                color: "#d4d4d4",
                borderRadius: 6,
                padding: 8,
                fontFamily: "Consolas, monospace",
                fontSize: 11,
                lineHeight: 1.45,
              }}
            >
              {filteredLog.length === 0 ? (
                <span style={{ color: "#888" }}>发送一次对话后，此处显示 run.* / reasoning.step / rag.retrieved / approval.* 等</span>
              ) : (
                filteredLog.map((e) => (
                  <div key={e.id} style={{ marginBottom: 10, borderBottom: "1px solid #333", paddingBottom: 8 }}>
                    <div>
                      <span style={{ color: "#569cd6" }}>[{e.ts}]</span>{" "}
                      <span style={{ color: "#ce9178" }}>{e.event}</span>
                      {e.traceId ? <span style={{ color: "#6a9955" }}> {e.traceId}</span> : null}
                    </div>
                    <div style={{ color: "#9cdcfe" }}>{e.summary}</div>
                  </div>
                ))
              )}
            </div>
          </section>
        </div>
      </div>

      <p style={{ fontSize: 12, color: "#888", marginTop: 20 }}>
        开发时代理：<code>/api</code> → <code>http://127.0.0.1:8080</code>。请先启动后端（如 <code>local</code> profile）。
      </p>
    </div>
  );
}
