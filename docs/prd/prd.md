产品需求文档 (PRD)：Nexus-Java 自主 Agent 框架
1. 文档概述
本规划旨在构建一个基于 Java 生态、具备自主推理与工具调用能力的 Agent 框架。该 Agent 能够理解复杂指令，通过推理链（Reasoning Chain）决定行动方案，并安全地调用外部系统 API 解决问题。

2. 核心功能需求
2.1 自主推理引擎 (Reasoning Engine)
ReAct 循环： 必须支持“Thought（思考）- Action（行动）- Observation（观察）”的循环逻辑。

多模型适配： 抽象模型层，支持通过配置文件切换 OpenAI、Anthropic 或本地 DeepSeek 模型。

终止条件： 具备明确的“任务完成”或“无法解决”判定逻辑，防止无限循环消耗 Token。

2.2 动态工具箱 (Tooling System)
声明式工具定义： 支持通过 Java 注解（如 @Tool）快速将 Service 层方法暴露给 Agent。

参数自动解析： 能够自动将 LLM 生成的 JSON 参数映射为 Java POJO 对象。

结果反馈： 工具执行的结果（包括异常信息）必须实时回传给推理引擎进行下一步决策。

2.3 记忆管理 (Memory Management)
短期会话： 维护单次对话的上下文，支持 Token 窗口滑动删除。

持久化存储： 支持将对话历史存储于 Redis 或数据库，实现 Session 的跨服务重启恢复。

长期知识（RAG 扩展）： 基于 Elasticsearch 构建知识索引，支持向量化检索与（可选）全文混合检索，实现检索增强生成。

3. 技术选型
开发语言： Java 25

基础框架： Spring Boot 3.x

Agent 核心库： LangChain4j (核心库)

API 协议： RESTful / SSE (流式输出)

数据存储： MySQL (业务数据) + Redis (记忆)

检索与 RAG： Elasticsearch（知识 Chunk 存储；dense_vector kNN；可选 BM25 + 向量混合检索，详见技术设计 `docs/design/rag-with-elasticsearch.md`）

4. 业务场景示例
自动化运维： “帮我查一下昨日订单量异常的日志，并总结前三个错误原因。”

数据汇总： “读取数据库中所有的待处理任务，调用翻译插件将其翻译成英文，并发送邮件给经理。”

5. 非功能性需求
安全性： 工具调用需具备权限隔离，写操作（如删除、修改数据）必须支持人工确认（Human-in-the-loop）。

可观测性： 每一轮的“思考过程”必须记录日志，支持在前端展示推理链路。

稳定性： 引入熔断机制，当 LLM 响应异常或工具调用超时时，Agent 能优雅退避。

性能： 对于大数据量的处理，必须采用流式读取（Streaming）模式，严禁一次性加载进内存以防止 OOM。