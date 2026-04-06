# 知识库构建开发文档（MVP 精简版）

## 1. 目标

先实现一个可独立运行的知识库构建程序，只做两件事：

1. 输入文档 -> 切片 -> 向量化 -> 写入向量库（Chroma）。
2. 输入文档 -> 实体关系抽取 -> 写入图数据库（Neo4j）。

不做复杂任务编排、版本切换、增量更新、调度平台。

---

## 2. 设计结论

知识库构建与在线主链路必须分开运行。

- 在线主链路：`Run -> RAG 检索 -> 生成回答`
- 离线构建链路：`文档处理 -> 切片 -> 同源双通道入库（Vector + Graph）`

离线构建通过单独命令启动，不放在用户请求线程内执行。

关键约束：图谱构建**不依赖向量结果**，向量链路与图谱链路都直接使用同一份输入文本切片（chunk）。

---

## 3. MVP 范围

## 3.1 必做

- 支持输入 Markdown/TXT/PDF 文件。
- 固定切片策略（字符窗口 + overlap）。
- 调用 Embedding 模型完成向量化。
- 向 Chroma Upsert chunk 向量。
- 从 chunk 文本抽取实体与关系。
- 向 Neo4j MERGE 节点与关系。
- 命令行执行一次构建并输出结果统计。

## 3.2 暂不做

- 任务队列与分布式调度。
- 多版本发布与灰度切换。
- 增量更新与自动删除同步。
- 复杂监控告警体系。

---

## 4. 运行方式（单独跑）

建议提供独立命令入口，例如：

```bash
java -jar backend.jar --kb.build.enabled=true --kb.build.input="docs/kb/*.md" --kb.build.kb-id=default_kb
```

或本地开发命令（示例）：

```bash
mvn -pl backend spring-boot:run -Dspring-boot.run.arguments="--kb.build.enabled=true --kb.build.input=docs/kb/test.md --kb.build.kb-id=demo"
```

程序启动后执行构建，完成即退出。

---

## 5. MVP 流程

先统一切片，再从同一批 chunk 分别写入向量库和图数据库。

```text
读取文档 -> 切片(统一chunk)
             ├─ 向量链路: chunk -> Embedding -> Chroma Upsert
             └─ 图谱链路: chunk -> 实体关系抽取 -> Neo4j MERGE
```

## 5.1 文档读取

- 输入参数：文件路径或目录。
- 输出：标准化文本 + 文档元数据（`kbId/documentId/sourceUri`）。

## 5.2 切片

- `chunkSize=600`
- `chunkOverlap=80`
- 每个 chunk 生成：
  - `chunkId`（建议：`sha256(documentId + index + content)`）
  - `chunkIndex`
  - `content`

## 5.3 向量化与向量入库（基于同一批 chunk）

- 使用 BGE-M3（或兼容 embedding provider）。
- 将每个 chunk 写入 Chroma：
  - `id=chunkId`
  - `document=content`
  - `embedding=vector`
  - `metadata={kbId, documentId, sourceUri, chunkIndex}`

## 5.4 图谱抽取与图入库（基于同一批 chunk）

- 从每个 chunk 提取实体和关系，输出三元组：
  - `(subject)-[relation]->(object)`
- Neo4j 写入策略：
  - 节点：`MERGE (n:Entity {name: $name, kbId: $kbId})`
  - 关系：`MERGE (a)-[r:REL {type: $type, kbId: $kbId}]->(b)`
- 追溯字段：建议为节点或关系附加 `sourceChunkId`，保证检索命中可回溯到原文。

---

## 6. 建议代码结构（最小实现）

```text
backend/src/main/java/com/nexus/agent/kb/
  KbBuildRunner.java              # 启动入口（命令模式）
  KbBuildProperties.java          # 配置
  pipeline/
    KbBuildPipeline.java          # 总流程编排
    DocumentLoader.java           # 文档读取
    Chunker.java                  # 切片
    EmbeddingService.java         # 向量化
    VectorStoreWriter.java        # Chroma 写入
    GraphExtractor.java           # 实体关系抽取
    GraphStoreWriter.java         # Neo4j 写入
  model/
    KbDocument.java
    KbChunk.java
    Triple.java
```

---

## 7. 配置（MVP）

```yaml
kb:
  build:
    enabled: false
    kb-id: default_kb
    input: docs/kb/*.md
    chunk-size: 600
    chunk-overlap: 80
    embedding-model: bge-m3
    vector:
      provider: chroma
      endpoint: http://localhost:8000
      collection: nexus_kb
    graph:
      provider: neo4j
      uri: bolt://localhost:7687
      username: neo4j
      password: ${NEO4J_PASSWORD:}
```

---

## 8. 验收标准（只看 MVP）

1. 能通过单独命令启动构建程序。
2. 指定文档可成功切片并写入 Chroma。
3. 同批文档可抽取实体关系并写入 Neo4j。
4. 程序输出构建统计：
   - `documentCount`
   - `chunkCount`
   - `vectorUpsertCount`
   - `entityCount`
   - `relationCount`
5. 任一环节失败时返回非 0 退出码并输出错误原因。

---

## 9. 实施步骤（建议）

1. 先打通“文档 -> 切片 -> Chroma”。
2. 再补“实体关系抽取 -> Neo4j”。
3. 最后补命令入口与统计输出。

---

## 10. 结论

当前阶段只做独立可运行的构建程序即可，目标是尽快形成可用数据底座：

- 向量库可检索；
- 图谱可查询；
- 与在线主链路解耦。

后续再逐步扩展任务调度、增量更新和可观测能力。
