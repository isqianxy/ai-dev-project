# API 设计要点（RESTful / SSE）

## 1. 需求映射

- **API 协议**：RESTful / SSE（流式输出）
- **可观测性**：前端可展示推理链路
- **安全性**：写工具需要人工确认

## 2. 契约管理

- OpenAPI 规范文件统一落位于：`docs/api/openapi.yaml`（后续在 API 阶段完善）
- 所有接口变更必须同步更新 OpenAPI，并记录变更到：`docs/api/changelog.md`（后续补充）

## 3. 资源模型（建议）

- **Session**：会话资源，用于承载上下文与权限边界
- **Task/Run**：一次任务执行实例（对应一次 ReAct 循环生命周期）
- **Event**：推理链与工具调用事件（SSE 输出与可追溯落库）
- **Approval**：人工确认请求（写操作/高风险操作）
- **KnowledgeBase / Document（RAG）**：知识库、文档版本、入库任务状态（主数据多在 MySQL，API 暴露统一资源）
- **Knowledge Search（RAG）**：对内由编排器调用，对外可暴露「调试/管理」型检索 API（需权限）；执行 **Embedding + ES 查询**，返回带 `source_uri` / `chunk_id` 的片段列表

## 4. SSE 事件类型（建议）

- `run.started`：任务开始
- `reasoning.step`：推理步骤（可包含 stepId、阶段、摘要）
- `tool.invoked`：工具调用开始
- `tool.result`：工具结果/异常（Observation）
- `approval.requested`：请求人工确认
- `approval.resolved`：确认结果
- `run.completed`：任务完成
- `run.failed`：任务失败
- `rag.retrieved`（可选）：RAG 检索完成（命中条数、耗时、`knowledge_base_id`；正文可不全量输出以便审计与脱敏）

## 5. 错误响应约定（建议）

- 统一错误结构：错误码、错误信息、可重试标记、关联 stepId/runId
- 参数解析失败、权限拒绝、超时、熔断均需要可区分的错误码
