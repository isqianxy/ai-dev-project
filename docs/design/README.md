# 技术设计文档（Design）

本目录用于沉淀 PRD 对应的技术方案、架构与约束，作为后续 API 契约与研发实现的依据。

## 文档清单

- `system-architecture.md`：整体架构与组件边界
- `reasoning-engine.md`：推理引擎（ReAct 循环、终止条件）
- `tooling-system.md`：工具系统（注解暴露、参数映射、结果回传）
- `memory-management.md`：记忆管理（短期会话、持久化、RAG 与 ES）
- `rag-with-elasticsearch.md`：基于 Elasticsearch 的 RAG 专项方案（索引、检索、混合排序、与 MySQL 协同）
- `rag-and-mcp-implementation-flow.md`：RAG 与 MCP 在当前代码中的实现链路详解（配置、时序、降级、排障）
- `api-design.md`：REST/SSE 设计要点与契约落位
- `data-design.md`：MySQL/Redis/Elasticsearch 使用边界与数据模型草案（RAG）
- `security-and-permission.md`：权限隔离与 Human-in-the-loop
- `observability.md`：日志、链路与推理链展示
- `stability-and-performance.md`：熔断、超时、流式处理与资源约束
- `testing-strategy.md`：单元/集成测试策略与质量门禁
