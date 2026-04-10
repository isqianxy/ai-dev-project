# RAG 重构技术方案（以 ragProject 为唯一基线）

## 1. 目标声明

本方案用于在当前项目中**完全替换旧 RAG 实现**，不再以 `mock/local_vector/elastic(旧实现)` 的兼容延续为目标。  
实施原则：以 `docs/design/ragProject.md` 的 Hybrid-RAG 方案为主，并按 LangChain4j Advanced RAG 的标准组件进行工程化落地。

---

## 2. 重构范围

## 2.1 保留项（不重构）

- `Session/Run/SSE` 主流程与 API 契约保持不变。
- 工具系统（ToolProvider、审批、MCP）保持不变。
- 记忆管理（SessionMemoryService）保持不变。

## 2.2 替换项（全量重构）

- 替换 `backend/src/main/java/com/nexus/agent/service/rag/*` 旧检索实现。
- 替换 `ReasoningEngineService` 中“手工拼接 RAG 上下文”逻辑为 `RetrievalAugmentor` 机制。
- 替换 `agent.rag.*` 配置结构为 Advanced RAG 组件配置。
- 替换前端 `rag.retrieved` 事件载荷为新结构。

---

## 3. 主链路架构（LangChain4j Advanced RAG）

## 3.1 组件映射

基于 LangChain4j Advanced RAG 组件，项目映射如下：

- `QueryTransformer`：实现 Query2Doc 扩写 + 实体提取（输出多 Query）。
- `QueryRouter`：将 Query 路由到 `vector` 与 `graph` 两类 `ContentRetriever`。
- `ContentRetriever`：
  - `ChromaVectorContentRetriever`
  - `Neo4jGraphContentRetriever`
- `ContentAggregator`：融合、去重、RRF/加权、可选 rerank。
- `ContentInjector`：将 Top-N 结果按来源标记注入用户消息。
- `RetrievalAugmentor`：统一编排入口，挂载到主链路。

## 3.2 运行时流程

```text
UserMessage
  -> RetrievalAugmentor
     -> QueryTransformer (Query2Doc + Entity Linking)
     -> QueryRouter
        -> ChromaVectorContentRetriever
        -> Neo4jGraphContentRetriever
     -> ContentAggregator (dedup + rerank + cutoff)
     -> ContentInjector
  -> Augmented UserMessage
  -> LLM Generate
```

## 3.3 设计约束

- 并行召回：Vector 与 Graph 同时执行。
- 同源追溯：最终注入内容必须可追溯 `chunkId/source`。
- 稳定降级：任一分支失败不影响主链路完成。

---

## 4. 检索链路分阶段设计

## 4.1 Pre-Retrieve（QueryTransformer）

- Query2Doc：将原始问题扩写为 1~3 条语义检索查询。
- Entity Linking：提取图谱查询关键实体与关系意图。
- 输出：`List<Query>`（向量查询 + 图谱查询）。

## 4.2 Retrieve（QueryRouter + ContentRetriever）

### Vector 路径

- 使用 bge-m3 embedding。
- Chroma Top-K 召回。
- 返回 `Content(TextSegment)`，metadata 包含 `chunkId/source/documentId`。

### Graph 路径

- 采用“Cypher 模板 + 槽位填充”，MVP 不做 LLM 自由生成 Cypher。
- 2-hop 路径检索。
- 路径结果反查证据 `chunkId`，再转换为 `Content`。

## 4.3 Post-Retrieve（ContentAggregator + ContentInjector）

- 聚合：多 Query + 多 Retriever 结果合并。
- 去重：按 `chunkId/hash/source` 去重。
- 排序：RRF/加权融合，可选 reranker 二次排序。
- 截断：按阈值与 `top-k-final` 输出。
- 注入：统一格式注入（含来源信息）。

---

## 5. 代码落位与改造点

## 5.1 新增组件（建议路径）

- `backend/.../service/rag/augmentor/HybridRetrievalAugmentorFactory.java`  
  组装 `DefaultRetrievalAugmentor` 与各子组件。

- `backend/.../service/rag/query/HybridQueryTransformer.java`  
  实现 Query2Doc + Entity Linking。

- `backend/.../service/rag/router/HybridQueryRouter.java`  
  实现 Query 到 retriever 的路由策略。

- `backend/.../service/rag/retriever/ChromaVectorContentRetriever.java`  
  向量检索实现。

- `backend/.../service/rag/retriever/Neo4jGraphContentRetriever.java`  
  图检索实现（模板 Cypher + 路径转内容）。

- `backend/.../service/rag/aggregate/HybridContentAggregator.java`  
  融合/去重/排序。

- `backend/.../service/rag/injector/HybridContentInjector.java`  
  注入模板与 metadata 格式控制。

## 5.2 现有类改造

- `ReasoningEngineService`  
  从手工 `ragService.retrieve + mergeContext` 切换到 `retrievalAugmentor` 调用。

- `RagService`  
  从 provider 选择器改造成主链路 Facade（可保留壳层）。

- `RagProperties`  
  新增 `advanced` 配置对象，支持 transformer/router/retriever/aggregator/injector 参数。

- `frontend/src/App.tsx`  
  适配新 `rag.retrieved` 载荷结构。

---

## 6. 配置模型（Advanced RAG）

```yaml
agent:
  rag:
    enabled: true
    mode: advanced_hybrid
    timeout-ms: 2500
    top-k-final: 5
    advanced:
      query-transformer:
        query2doc-enabled: true
        query-expand-count: 3
        entity-linking-enabled: true
      query-router:
        route-vector: true
        route-graph: true
      vector:
        provider: chroma
        top-k: 20
        min-score: 0.0
        endpoint: http://localhost:8000
        collection: nexus_kb_1024
      graph:
        provider: neo4j
        max-depth: 2
        timeout-ms: 1200
        cypher-template-enabled: true
      aggregator:
        strategy: rrf
        dedup: chunk_id
        rerank-enabled: false
        rerank-threshold: 0.40
      injector:
        include-metadata-keys: [source, chunkId, score]
```

---

## 7. 事件与可观测性

保留事件名：`rag.retrieved`。  
建议载荷：

- `queryId`
- `retrieverRoutes`（vector/graph）
- `candidateCount`
- `finalCount`
- `latencyMs`
- `degraded`
- `fallbackReason`
- `sources`

---

## 8. 错误处理与降级策略

1. Graph 路径失败 -> 自动降级 Vector-only。
2. Vector 路径失败且 Graph 成功 -> Graph-only（标记降级）。
3. 双路径失败 -> 注入空上下文，主链路继续。
4. 任一路径超时 -> 结束该路径并记录降级原因。
5. Cypher 非法 -> 拒绝执行并记录安全事件。

---

## 9. 实施计划（开发顺序）

1. 接入 `RetrievalAugmentor` 框架，替换主链路注入点。
2. 先完成 `ChromaVectorContentRetriever`（跑通纯向量）。
3. 接入 `Neo4jGraphContentRetriever`（模板 Cypher）。
4. 接入 `HybridContentAggregator`（先 RRF，不启用 rerank）。
5. 完成前端事件适配与回归测试。

---

## 10. 验收标准

## 10.1 功能

- 复杂关系问题可命中 Graph 路径信息。
- 描述性问题可命中 Vector 语义片段。
- 融合后输出质量优于单路检索（固定测试集评估）。

## 10.2 稳定性

- 分支失败不导致 Run failed。
- P95 检索耗时满足目标（建议 <= 2.5s）。

## 10.3 可观测

- 每次检索可追踪路由、命中、耗时、降级。
- 支持按 `traceId/runId` 回放检索行为。

---

## 11. 测试计划

1. 单元测试：Query 转换、路由、去重、排序、注入格式。
2. 集成测试：Chroma + Neo4j 双检索闭环。
3. 回归测试：Session/Run/SSE/审批链路不回归。
4. 压测：并发场景耗时与错误率。

---

## 12. 风险与回滚

## 12.1 风险

- QueryTransformer 引入额外 token 与时延。
- 图路径召回可能带来噪声，需依赖聚合排序控制。
- rerank 若开启会增加成本与延迟。

## 12.2 回滚

- 配置 `agent.rag.enabled=false` 临时关闭 RAG。
- 配置 `route-graph=false` 回退 Vector-only。
- 保留可回滚版本，逐步灰度切换。

---

## 13. 结论

本方案在保持 `ragProject.md` 核心思想的前提下，采用 LangChain4j Advanced RAG 组件化架构完成主链路重构，实现“可扩展、可观测、可降级”的 Hybrid-RAG 检索系统。后续开发以本文件为实施基线。
