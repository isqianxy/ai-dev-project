# 系统架构设计（Nexus-Java 自主 Agent 框架）

## 1. 目标与范围

- **目标**：构建基于 Java/Spring Boot 的自主 Agent 框架，支持推理循环、工具调用、记忆管理与可观测能力，并可通过配置切换不同 LLM 提供方。
- **范围**：本设计覆盖框架级能力（推理引擎/工具系统/记忆/接口/安全/可观测/稳定性），不涉及具体业务场景实现。

## 2. 架构原则

- **前后端分离**：后端提供 REST/SSE 接口；前端仅负责展示、交互与观测面板。
- **契约先行**：API 以 OpenAPI 为单一事实来源（Single Source of Truth），文档落位于 `docs/api/`。
- **可回滚**：每次迭代以小步提交与清晰变更记录为原则，API 变更需记录到 `docs/api/changelog.md`（后续补充）。
- **安全默认**：工具调用分级授权；写操作默认需要人工确认（Human-in-the-loop）。

## 3. 逻辑组件

- **API 层（Gateway/Controller）**
  - 对外提供：会话管理、任务提交、SSE 流式输出、运行状态查询、推理链路查询等。
- **Agent Orchestrator（编排器）**
  - 负责：一次任务的生命周期管理、循环驱动、终止判定、错误退避、资源限制。
- **Reasoning Engine（推理引擎）**
  - 负责：ReAct 循环（思考-行动-观察）、下一步决策、终止条件判定输入。
- **Tooling System（工具系统）**
  - 负责：工具声明、参数解析（JSON→POJO）、执行、结果/异常反馈、权限校验切面。
- **Memory Management（记忆系统）**
  - 负责：短期上下文窗口、持久化（Redis/DB）、长期知识检索接口（RAG：由 ES 承载，见 `rag-with-elasticsearch.md`）。
- **Knowledge / RAG 检索服务（与记忆协同）**
  - 负责：知识入库编排、Chunk 向量化写入 ES、查询时 kNN/混合检索、结果注入 THINK 上下文；失败时降级。
- **Model Adapter（模型适配层）**
  - 负责：统一的 LLM 调用接口与供应商实现（OpenAI/Anthropic/DeepSeek 等），通过配置切换。
- **Observability（可观测模块）**
  - 负责：推理链路事件、工具调用事件、指标与日志聚合，供前端展示。

## 4. 部署与依赖

- **后端**：Spring Boot 3.x，Java 25。
- **核心库**：LangChain4j（用于 LLM 与链式编排能力）。
- **存储**：MySQL（业务数据/审计/事件落库），Redis（会话与短期/持久化记忆）。
- **检索与 RAG**：Elasticsearch（知识 Chunk + `dense_vector` 语义检索；可选与全文 BM25 混合排序）。
- **通讯**：RESTful + SSE（流式输出）。

## 5. 数据流（高层）

1. 客户端提交任务（REST）。
2. 编排器创建/绑定 Session，初始化短期上下文。
3. 推理引擎进入 ReAct 循环；若在 THINK 阶段启用 RAG，则经 ES 检索 Top-K 片段注入上下文；必要时触发工具调用。
4. 工具系统完成权限校验、参数解析、执行，并将 Observation 回传。
5. 事件流（SSE）持续向前端推送：推理步骤、工具执行结果、状态变更。
6. 满足终止条件后结束，落库关键事件与可追溯信息。
