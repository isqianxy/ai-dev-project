# API 变更记录

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
