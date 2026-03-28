# 基于 Elasticsearch 的 RAG 方案（详细设计）

## 1. 目标与定位

- **目标**：在现有 Agent 框架中，以 **Elasticsearch（ES）** 作为长期知识存储与检索引擎，实现「文档入库 → 向量化 → 相似度/混合检索 → 注入推理上下文」的 RAG 闭环。
- **定位**：RAG 是**可选增强能力**：ES 不可用或检索失败时，Agent 必须能降级为「无知识增强」模式，主推理链路不中断（与 PRD 稳定性要求一致）。

## 2. 为何选用 Elasticsearch

| 维度 | 说明 |
|------|------|
| **向量检索** | ES 8.x 提供 `dense_vector` 与 **kNN 检索**（`knn` query），满足语义召回。 |
| **混合检索** | 同一索引内可同时使用 **BM25（全文）** 与 **向量 kNN**，并通过 **RRF（Reciprocal Rank Fusion）** 等能力做融合排序，缓解纯向量漏召/偏召问题（建议 ES 8.8+ 使用 RRF）。 |
| **元数据过滤** | 按 `tenant_id`、`knowledge_base_id`、权限标签等 **filter** 再 kNN，满足多租户与数据边界。 |
| **运维与生态** | 与日志、可观测、现有搜索栈统一；快照、ILM、监控体系成熟。 |
| **与关系库分工** | **MySQL** 存知识库/文档/版本等业务主数据；**ES** 存面向检索的 **Chunk + 向量 + 检索字段**；避免在 MySQL 上做高维检索。 |

**版本建议**：生产环境优先 **Elasticsearch 8.10+**（或兼容的 OpenSearch 2.x 若团队统一开源分支，需在项目内固定一种并写进部署清单；本文默认 ES 8.x API）。

## 3. 总体架构

```
┌─────────────────────────────────────────────────────────────────┐
│                     应用层（Spring Boot）                          │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────────────┐   │
│  │ 知识入库 API  │  │ Embedding    │  │ RAG 检索服务         │   │
│  │ (异步任务)    │→ │ (独立适配)   │← │ (kNN + 可选混合)     │   │
│  └──────┬───────┘  └──────┬───────┘  └──────────┬───────────┘   │
│         │                 │                        │               │
│         └────────────────┼────────────────────────┘               │
│                          ▼                                        │
│               Elasticsearch 集群（知识索引）                        │
└─────────────────────────────────────────────────────────────────┘
                          ▲
                          │ 同步状态 / 文档版本（可选）
┌─────────────────────────┴───────────────────────────────────────┐
│  MySQL：knowledge_base、document、chunk_job、审计等（主数据）      │
└─────────────────────────────────────────────────────────────────┘

推理时：Reasoning Engine 在 THINK 阶段调用「RAG 检索服务」→ 将 Top-K 片段注入 Prompt（带引用标记），再继续 ReAct。
```

- **Embedding**：与对话 LLM 解耦；可使用专用 Embedding API 或本地模型，维度需与索引 `dense_vector` **dims** 一致。
- **写入路径**：建议 **异步**（消息队列或 DB 状态机 + 定时补偿），避免大文档阻塞 API；**同步**仅用于小文本/MVP。

## 4. 索引设计（Index Mapping）

### 4.1 索引命名

- 建议：`agent_knowledge_chunks`（单索引多租户）或按租户分索引 `agent_kb_{tenantId}`（隔离更强，运维成本更高）。**默认推荐单索引 + `tenant_id` filter**。

### 4.2 字段建议

| 字段 | 类型 | 说明 |
|------|------|------|
| `chunk_id` | keyword | 全局唯一 Chunk ID（可与 MySQL 主键一致） |
| `tenant_id` | keyword | 租户隔离，**检索必须带 filter** |
| `knowledge_base_id` | keyword | 知识库 ID |
| `document_id` | keyword | 逻辑文档 ID |
| `source_uri` | keyword | 来源标识（文件路径、URL、业务主键等） |
| `chunk_index` | integer | 文档内序号 |
| `title` | text + keyword 子字段 | 标题检索 |
| `content` | text | **BM25 全文**，存放 Chunk 纯文本 |
| `content_vector` | **dense_vector** | 向量；`index: true`，`similarity: cosine`（或 `dot_product`，与 Embedding 归一化策略一致） |
| `metadata` | object / flattened | 业务标签、权限 scope、过期时间等 |
| `embedding_model` | keyword | 使用的 Embedding 模型版本，便于重建索引 |
| `created_at` | date | 入库时间 |

### 4.3 Mapping 要点

- `dense_vector` 的 **dims** 必须与 Embedding 输出维度一致（例如 1536、1024）。
- 对 `tenant_id`、`knowledge_base_id` 建立检索前 **严格 filter**，防止越权召回。
- 大文本先 **切 Chunk** 再入索引；单字段体积控制在合理范围，避免单次 `_source` 过大。

## 5. 文档切分（Chunking）

- **策略（由简到繁）**：
  1. **固定字符/ Token 窗口 + 重叠**（MVP）。
  2. 按标题/段落/ Markdown 结构切分（提升语义边界）。
  3. 表格、代码块单独规则（避免切断语法）。
- **元数据**：每个 Chunk 携带 `document_id`、`chunk_index`、`source_uri`，便于前端展示「引用来源」与审计。

## 6. 检索策略

### 6.1 纯向量 kNN（基线）

- 对用户查询做 Embedding，使用 ES `knn` query，`k` 取 20～50，再在应用层 **重排序**或截断为 Top-5～10 注入上下文。
- **filter**：`tenant_id` + `knowledge_base_id` + 可选 `metadata` 权限标签。

### 6.2 混合检索（推荐生产）

- **一路**：`content` 上 **BM25**（query string / multi_match）。
- **一路**：`content_vector` **kNN**。
- 使用 **RRF** 合并两路结果（需确认集群版本能力），或应用层加权融合。
- **效果**：关键词极强的查询（工单号、专有名词）与语义泛化查询兼顾。

### 6.3 注入推理上下文

- 拼接格式建议：每条命中带 `[来源: source_uri#chunk_index]`，控制总 Token（截断、摘要二次调用可选）。
- **禁止**：无过滤的「全库 kNN」，必须在业务边界内检索。

## 7. 与 Agent 的衔接

- **调用时机**：ReAct 的 **THINK** 前或首轮 THINK 内：根据用户问题触发检索（也可由工具显式调用 `knowledge_search`，便于复用与观测）。
- **失败策略**：ES 超时 → 记录事件 → **跳过 RAG**；部分shard失败 → 降级为可用分片或返回空结果；**不向用户暴露堆栈**。
- **可观测**：建议 SSE 增加可选事件 `rag.retrieved`（命中数、耗时、kb_id，不含全文）。

## 8. API 与主数据（与 MySQL 协同）

- **入库**：REST 接收「创建知识库 / 上传文档 / 触发索引」；MySQL 记录文档版本与任务状态；Worker 拉取待处理任务 → 切分 → Embedding → `_bulk` 写入 ES。
- **删除/更新**：**先改 MySQL 状态，再 ES delete/update by query**（或以 `chunk_id` bulk delete）；避免 ES 与库不一致时可 **按 knowledge_base_id 全量重建**（运维工具）。
- **OpenAPI**：在 `docs/api/` 中单独定义 Knowledge / Ingest / Search 路径（与 Session/Run 解耦）。

## 9. 安全与合规

- 检索 **强制** `tenant_id` filter；与登录态/服务账号绑定。
- 敏感文档：不入库、或 `metadata` 打标 + 检索侧过滤；审计记录「谁检索了哪些 kb」。
- 向量与原文在 ES 中按集群安全策略做 **TLS、角色 RBAC**（Elastic Security / API Key）。

## 10. 性能与容量（实施检查项）

- **bulk 写入**：批量大小与 refresh 间隔调优；大批量入库使用 **异步 refresh**。
- **分片**：按数据量估算 primary shards；避免单分片过大。
- **冷数据**：ILM 将旧索引迁温/冷层（可选）。
- **查询 SLA**：kNN + filter 设置合理超时；并发用线程池隔离，防止拖垮主业务。

## 11. 测试验收（摘要）

- 单元测试：查询构建（filter 必填）、 Embedding 维度校验、空结果降级。
- 集成测试：嵌入测试容器或专用 ES 测试集群；验证：入库 → 检索 → 注入 → 推理引用链。
- 回归：同 query 下混合检索与纯向量结果可重复（固定 seed 或固定测试集）。

## 12. 实施阶段建议（与 work-plan M10 对齐）

1. 单索引 + 固定 chunk + 纯 kNN + 强制 tenant filter（MVP）。
2. 增加 BM25 + RRF（或应用层融合）。
3. 异步入库流水线 + MySQL 任务表 + 失败重试。
4. 观测与配额（按租户 QPS/索引大小）。

---

*本文档为 Elasticsearch RAG 专题设计；存储边界与关系库分工见 `data-design.md`，记忆分层见 `memory-management.md`。*
