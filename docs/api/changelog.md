# API 变更记录

## 0.9.0（M10）

- SSE 事件新增 `rag.retrieved`，用于观测 RAG 检索提供方与命中数。
- 新增配置：`agent.rag.enabled`、`agent.rag.provider`、`agent.rag.top-k`。
- 支持 RAG 检索上下文注入（当前默认 `mock` provider，`elastic` provider 为占位实现，异常时可降级为空结果）。
- RAG 检索接口切换为 LangChain4j 抽象（`ContentRetriever`/`Query`/`Content`），便于后续直接接入 EmbeddingStore 与 RetrievalAugmentor。
- 破坏性变更：无（新增事件，向后兼容）。

## 0.8.0（M9）

- 新增稳定性配置：`agent.stability.circuit-breaker-enabled`、`agent.stability.circuit-failure-threshold`、`agent.stability.circuit-open-ms`。
- 模型连续失败达到阈值后触发熔断，熔断窗口内快速失败，`run.failed.reason` 可能返回 `CIRCUIT_OPEN`。
- 破坏性变更：无（行为增强，向后兼容）。

## 0.7.0（M7/M8）

- SSE 事件顶层新增 `traceId` 字段，用于链路追踪（`run.started/reasoning.step/tool.invoked/tool.result/approval.requested/run.completed/run.failed`）。
- 记忆能力增强：新增可切换记忆存储（`agent.memory.provider=in_memory|redis`），Redis 支持 `key-prefix` 与 `ttl-seconds`。
- 破坏性变更：无（新增字段，向后兼容）。

## 0.6.0（M6）

- `GET /api/v1/tools` 返回结构新增 `riskLevel` 字段（LOW/MEDIUM/HIGH）。
- 高风险工具（`riskLevel >= agent.tool-risk.block-level`）执行时将被拦截并返回 `TOOL_APPROVAL_REQUIRED`。
- 当运行中触发高风险工具拦截时，SSE 新增事件 `approval.requested`，并自动创建审批记录（含 `approvalId`）。
- 审批通过后可重试同一 run；若命中 `TOOL_EXECUTE:<toolName>` 的批准记录，该高风险工具允许一次执行（单次放行后失效）。
- 新增配置：`agent.tool-risk.enabled`、`agent.tool-risk.block-level`。
- 破坏性变更：`ToolInfoResponse` 新增必填字段 `riskLevel`（客户端若做严格 schema 校验需同步更新）。

## 0.5.0（M5）

- 新增工具系统接口：
  - `GET /api/v1/tools`（工具清单）
  - `POST /api/v1/tools/{toolName}/invoke`（调试调用）
- SSE 新增事件：`tool.invoked`、`tool.result`。
- `CreateRunRequest.prompt` 支持工具触发语法：`tool://<toolName> <json>`。
- 破坏性变更：无（向后兼容）。

## 0.4.0（M4）

- 接入模型适配层：支持 `agent.llm.provider=mock|deepseek` 切换。
- 在 `local` profile 下可调用 DeepSeek（`base-url/api-key/model`）。
- DeepSeek 客户端实现改为 `LangChain4j OpenAiChatModel`（OpenAI 兼容协议）。
- SSE 事件协议不变；`run.completed` 的 `detail` 增加 `provider/model/answer`（行为增强）。
- 破坏性变更：无（向后兼容）。

## 0.3.0（M3）

- `POST /api/v1/sessions/{sessionId}/runs` 新增可选请求体：`CreateRunRequest.prompt`。
- SSE 运行事件从 M1 Mock 升级为 M3 ReAct v0 规则引擎，新增失败事件 `run.failed`。
- 运行状态新增 `FAILED`，用于不可解/超时/最大步数等终止路径。
- 破坏性变更：无（向后兼容）。

## 0.2.0（M2）

- 新增运行状态查询：`GET /api/v1/runs/{runId}`。
- 新增审批流程接口：
  - `POST /api/v1/runs/{runId}/approvals`（创建审批）
  - `GET /api/v1/approvals/{approvalId}`（查询审批）
  - `POST /api/v1/approvals/{approvalId}/resolve`（确认审批）
- `CreateRunResponse` 新增 `createdAt`、`updatedAt` 字段。
- 新增错误码：`APPROVAL_NOT_FOUND`、`INVALID_DECISION`、`APPROVAL_ALREADY_RESOLVED`。
- 破坏性变更：无（仅新增字段与接口）。

## 0.1.0（M1）

- 初始契约：`POST /api/v1/sessions`、`POST /api/v1/sessions/{sessionId}/runs`、`GET /api/v1/runs/{runId}/events`（SSE）。
- 破坏性变更：无（首版）。
