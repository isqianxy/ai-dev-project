# API 变更记录

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
