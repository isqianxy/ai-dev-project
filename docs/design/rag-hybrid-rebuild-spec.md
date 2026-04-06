# RAG 重构技术方案（以 ragProject 为唯一基线）

## 1. 目标声明

本方案用于在当前项目中**完全替换旧 RAG 实现**，不再以 `mock/local_vector/elastic(旧实现)` 的兼容延续为目标。  
实施原则：以 `docs/design/ragProject.md` 的 Hybrid-RAG 方案为主，落地为可开发、可测试、可回滚的工程实现。

---

## 2. 重构范围

## 2.1 保留项（不重构）

- `Session/Run/SSE` 主流程与 API 契约保持不变。
- 工具系统（ToolProvider、审批、MCP）保持不变。
- 记忆管理（SessionMemoryService）保持不变。

## 2.2 替换项（全量重构）

- 替换 `backend/src/main/java/com/nexus/agent/service/rag/*` 旧检索实现。
- 替换 `ReasoningEngineService` 中“旧 RAG 结果拼接”相关逻辑为新编排调用。
- 替换 `agent.rag.*` 配置结构（保留 `agent.rag.enabled` 顶层开关）。
- 替换前端 `rag.retrieved` 事件展示字段为新结构。

---

## 3. 目标架构（Hybrid-RAG）

```text
User Query
   -> HybridRagOrchestrator
      -> 并行分支A: VectorRetriever (Chroma / 向量库)
      -> 并行分支B: GraphRetriever  (Neo4j / 图检索)
      -> FusionRerankService (聚合、去重、精排、阈值过滤)
   -> Top-N Context
   -> LLM Generation
```

核心要求：

- 并行召回：Vector 与 Graph 同时执行。
- 异构融合：统一候选结构后进行去重与重排。
- 稳定降级：Graph 分支失败时自动降级到 Vector-only，不中断请求。

---

## 4. 检索链路设计

## 4.1 第一阶段：并行召回

### A. Vector Branch

1. Query2Doc：对原始问题进行语义扩写（可配置开关）。
2. Embedding：使用 BGE-M3（或兼容实现）向量化。
3. Vector Search：在 Chroma 检索 Top-20 文本片段。

### B. Graph Branch

1. Entity Linking：从 Query 识别关键实体。
2. Cypher Task：基于约束 schema 生成 Cypher。
3. Path Traversal：执行 2-Hop 图检索，输出路径描述文本。

## 4.2 第二阶段：融合与精排

1. Collect：合并 Vector 片段与 Graph 文本结果。
2. De-duplication：按 `id/hash/source` 去重。
3. Rerank：调用 BGE-Reranker-v2-m3 统一打分。
4. Dynamic Cutoff：保留 `score > threshold` 且 Top-N（默认 5）。

## 4.3 第三阶段：答案生成

- 将精排后上下文注入 LLM Prompt。
- 上下文必须带来源信息，便于前端可观测与审计追踪。

---

## 5. 组件与代码落位

## 5.1 新增核心组件（建议路径）

- `backend/.../service/rag/HybridRagOrchestrator.java`  
  负责并行调度与阶段编排。

- `backend/.../service/rag/vector/ChromaVectorRetriever.java`  
  负责 Query2Doc、Embedding、向量检索。

- `backend/.../service/rag/graph/Neo4jGraphRetriever.java`  
  负责实体识别、Cypher、路径检索与文本化。

- `backend/.../service/rag/fusion/FusionRerankService.java`  
  负责去重、重排、阈值截断。

- `backend/.../service/rag/model/*`  
  定义 `RagCandidate / RagChannelResult / RagFusionResult` 等统一数据结构。

## 5.2 现有类改造

- `ReasoningEngineService`  
  改为仅调用 `HybridRagOrchestrator.retrieve(...)`，不再依赖旧 provider 选择逻辑。

- `RagProperties`  
  改为 Hybrid 配置模型，不保留 `mock/local_vector/elastic` 旧 provider 分支字段。

- `frontend/src/App.tsx`  
  展示新 `rag.retrieved` 字段：`channels/hits/latency/degraded/fallbackReason`。

---

## 6. 配置模型（最终形态）

```yaml
agent:
  rag:
    enabled: true
    mode: hybrid
    timeout-ms: 2500
    top-k-final: 5
    rerank-threshold: 0.40
    vector:
      enabled: true
      provider: chroma
      top-k: 20
      embedding-model: bge-m3
      query2doc-enabled: true
      query2doc-max-tokens: 128
      chroma:
        endpoint: http://localhost:8000
        collection: nexus_kb
        timeout-ms: 1200
    graph:
      enabled: true
      provider: neo4j
      max-depth: 2
      timeout-ms: 1200
      entity-linking-model: deepseek-chat
      cypher-guard-enabled: true
      neo4j:
        uri: bolt://localhost:7687
        username: neo4j
        password: ${NEO4J_PASSWORD:}
    fusion:
      strategy: rerank_priority
      dedup: hash
      reranker-model: bge-reranker-v2-m3
      vector-weight: 0.5
      graph-weight: 0.5
```

---

## 7. 事件与可观测性

保留事件名：`rag.retrieved`。  
新事件载荷建议：

- `queryId`
- `channels`：`vector`/`graph` 命中明细
- `candidateCount`
- `finalCount`
- `latencyMs`
- `degraded`（是否降级）
- `fallbackReason`（如 `GRAPH_TIMEOUT` / `CYPHER_INVALID`）
- `sources`（最终上下文来源列表）

---

## 8. 错误处理与降级策略

1. Graph 分支失败 -> 自动降级 Vector-only。
2. Vector 分支失败且 Graph 成功 -> 使用 Graph-only（标记降级）。
3. 双分支失败 -> 返回空上下文，主链路继续。
4. 任一分支超时 -> 记录降级原因并结束该分支，不阻塞全链路。
5. Cypher 非法 -> 拒绝执行并记录安全事件。

---

## 9. 与旧实现的切换策略

## 9.1 切换原则

- 不做“旧 provider 兼容分发”，直接切换到 `mode=hybrid`。
- 旧实现视为历史方案，仅保留短期回滚分支，不作为长期维护路径。

## 9.2 实施步骤

1. 新增 Hybrid 组件并联调通过。
2. 将 `ReasoningEngineService` 调用切换到新编排入口。
3. 前端适配新事件结构。
4. 删除旧 RAG provider 与相关配置字段。
5. 更新 API/设计文档与测试基线。

---

## 10. 验收标准

## 10.1 功能

- 复杂关系问题可命中 Graph 路径信息。
- 描述性问题可命中 Vector 语义片段。
- 融合后输出质量优于单路检索（以测试集打分验证）。

## 10.2 稳定性

- 分支失败不导致 Run failed。
- P95 检索耗时满足目标（建议 <= 2.5s）。

## 10.3 可观测

- 每次检索都可看到分支命中、耗时、降级原因。
- 支持按 `traceId/runId` 回放检索行为。

---

## 11. 测试计划

1. 单元测试：去重、重排、阈值、降级分支。
2. 集成测试：Chroma + Neo4j 联合检索闭环。
3. 回归测试：Session/Run/SSE/审批链路不回归。
4. 压测：并发场景下检索耗时与错误率。

---

## 12. 风险与回滚

## 12.1 风险

- Query2Doc 质量波动影响向量召回稳定性。
- Cypher 生成质量影响图检索可靠性。
- 融合参数初期可能导致排序不稳定。

## 12.2 回滚

- 配置开关 `agent.rag.enabled=false` 临时关闭 RAG。
- 将 `graph.enabled=false` 回退为 Vector-only 模式。
- 保留一个可回滚发布版本用于紧急恢复。

---

## 13. 结论

本方案明确采用 `ragProject.md` 的 Hybrid-RAG 作为唯一技术路线，目标是“直接替换旧实现”，并通过并行召回、融合精排与稳定降级实现更高质量的检索增强能力。后续开发应以本文件作为唯一实施规范。
