# 记忆管理设计（Memory Management）

## 1. 需求映射

- **短期会话**：维护单次对话上下文，支持 Token 窗口滑动删除。
- **持久化存储**：对话历史可存 Redis 或数据库，实现跨重启恢复。
- **长期知识（RAG 扩展）**：通过 **Elasticsearch** 持久化 Chunk 与向量，提供 kNN/混合检索能力，支持检索增强生成。

## 2. 记忆分层

- **短期上下文（Context Window）**
  - 仅保留最近若干轮 Thought/Action/Observation + 关键约束
  - 超出窗口时按策略裁剪（优先保留 Observation 与安全/权限约束）
- **会话持久化（Session Store）**
  - 存储 session 元数据、摘要、关键事件索引
  - 目标：可恢复、可追溯、可审计
- **长期知识（Knowledge Store，ES）**
  - 检索接口抽象：输入查询文本（+ 可选 filter：租户、知识库）→ **Embedding** → ES **kNN**（可选 **BM25** 混合）→ 返回带来源的文档片段 → 供推理引擎在 THINK 阶段引用

## 3. Redis 与数据库边界（建议）

- **Redis**：会话热数据、短期上下文缓存、确认请求临时状态
- **MySQL**：任务与会话归档、审计日志、推理链事件持久化、报表查询
- **Elasticsearch**：长期知识 Chunk、向量索引与检索（RAG）；详见 `rag-with-elasticsearch.md`

## 4. 隐私与合规

- 支持按配置对敏感字段脱敏/不落库
- 支持会话级过期与删除策略（TTL + 归档）
