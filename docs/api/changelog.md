# API 变更记录

## 0.6.0（M6）

- `GET /api/v1/tools` 返回结构新增 `riskLevel` 字段（LOW/MEDIUM/HIGH）。
- 高风险工具（`riskLevel >= agent.tool-risk.block-level`）执行时将被拦截并返回 `TOOL_APPROVAL_REQUIRED`。
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
