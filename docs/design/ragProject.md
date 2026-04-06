基于 Vector + Graph 的混合检索 RAG (Hybrid-RAG)
1. 设计目标
融合**向量库（Chroma）的非结构化语义召回能力与图数据库（Neo4j）**的结构化关系推理能力，解决单一检索方式在复杂长链条问题上表现不佳的问题。最后通过 BGE-Reranker 进行多源数据融合与去重。
2. 总体架构图
该架构采用并行异步触发模式，利用 LangGraph4j 管理节点状态流转。
3. 核心检索链路设计
3.1 第一阶段：并行召回 (Parallel Retrieval)
系统接收到 Query 后，同时开启两个独立线程：
链路 A：向量检索分支 (Vector Branch)
Query2Doc: 调用 LLM 根据原始 Query 扩写一段背景描述。
Embedding: 将扩写后的内容通过 BGE-M3 转化为向量。
Vector Search: 在 Chroma 中执行 Top-20 的向量检索，获取原始片段（Chunks）。
链路 B：图检索分支 (Graph Branch)
Entity Linking: 从 Query 中识别出关键实体。
Cypher Task: LLM 根据实体和预设 Schema 生成 Cypher 查询语句。
Path Traversal: 在 Neo4j 中执行 2-Hop 检索，获取关联的实体属性及关系链条（Paths），并将其转化为自然语言描述。

3.2 第二阶段：融合与精排 (Fusion & Rerank)
数据汇总 (Collect): 将 Vector 链路返回的文本块与 Graph 链路生成的文本描述合并。
去重 (De-duplication): 基于元数据（如 ID 或内容哈希）去除重复信息。
精排 (Rerank):将所有召回内容与原始 Query 组成 (Query, Passages) 对。输入 BGE-Reranker-v2-m3 进行二次打分。
动态截断: 仅保留评分 $S > 0.4$ 的前 5 条核心上下文。

3.3 第三阶段：答案生成 (Generation)
将精排后的高质量上下文喂给 LLM，生成最终回答。
4. LangGraph4j 节点实现逻辑
在 LangGraph4j 中，我们将检索过程建模为一个有向无环图 (DAG)：
节点名称,职责,输入状态,输出状态
input_node,参数解析与分流,User Query,query
vector_retriever,Query2Doc + Chroma 检索,query,vector_results
graph_retriever,Cypher + Neo4j 检索,query,graph_results
rerank_fusion,聚合、精排、去重,"vector_results, graph_results",fused_context
generator,最终答案生成,fused_context,final_answer
伪代码示例：
StateGraph<RagState> graph = new StateGraph<>(RagState.class)
    .addNode("vector_retriever", this::vectorSearchTask)
    .addNode("graph_retriever", this::graphSearchTask)
    .addNode("rerank_fusion", this::rerankTask)
    .addEdge(START, "vector_retriever")
    .addEdge(START, "graph_retriever")
    .addEdge("vector_retriever", "rerank_fusion")
    .addEdge("graph_retriever", "rerank_fusion")
    .addEdge("rerank_fusion", "generator")
    .addEdge("generator", END);
    
5. 关键技术细节
5.1 权重分配问题
虽然有了 Reranker，但在召回阶段仍可进行启发式权重干预。

Vector 权重: 侧重于解决“是什么”、“怎么样”的解释性问题。

Graph 权重: 侧重于解决“谁和谁有什么关系”、“流程先后顺序”的逻辑性问题。

5.2 异构数据对齐
Vector Data: 原始文本片段。

Graph Data: 三元组转换后的文本（例如：“张三 -> 居住在 -> 北京”）。

对齐策略: 在 Rerank 前，确保 Graph 返回的数据被封装成类似 Document 的对象，带有统一的 content 字段。

5.3 错误降级 (Fallback)
如果 Neo4j 检索失败或生成 Cypher 报错，系统应能无感降级，仅依靠 Vector 链路返回结果，而不是中断整个请求。

6. 配置参数 (application.yml)
YAML
rag:
  hybrid:
    enabled: true
    fusion-strategy: rerank_priority
    rerank-threshold: 0.45
    top-k-final: 5
  vector:
    k: 15
  graph:
    max-depth: 2
    timeout-ms: 2000