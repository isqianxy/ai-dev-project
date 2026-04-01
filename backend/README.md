# Nexus Agent 后端（Spring Boot）

## 环境

- JDK 21+（本仓库 `pom.xml` 默认 `java.version=21`；若本机已安装 Java 25，可直接使用）
- Maven 3.9+

## 启动（本地、无 Docker）

**方式 A（推荐）**：进入后端模块再启动（Maven 能识别 `spring-boot` 插件）：

```bash
cd backend
mvn spring-boot:run
```

**方式 B**：在**仓库根目录**（已提供根级 `pom.xml` 聚合 `backend`）：

```bash
mvn -pl backend spring-boot:run
```

不要在根目录直接执行 `mvn spring-boot:run`（不带 `-pl backend`），否则会报错：`No plugin found for prefix 'spring-boot'`。

默认端口：`http://localhost:8080`

## 常见问题

### 1. `SpringApplication cannot be resolved`（IDE 直接点运行）

原因：未通过 **Maven** 解析依赖，运行的是 Language Server 生成的不完整 classpath。

处理：

- 用 **Cursor / VS Code**：命令面板执行 **「Java: Clean Java Language Server Workspace」** 后重载；再打开 `backend/pom.xml`，等待右下角 **Maven 导入完成**。
- 或使用终端 **`mvn spring-boot:run`**（方式 A/B），不要对主类使用「Run Java」直到依赖已导入。

### 2. `No plugin found for prefix 'spring-boot'`

原因：在**没有 `pom.xml` 的目录**执行了 `mvn spring-boot:run`，或在根目录未指定模块。

处理：使用上文 **方式 A** 或 **方式 B**。

## 当前能力（M1~M5+）

- `POST /api/v1/sessions`：创建会话（内存存储，重启丢失）
- `POST /api/v1/sessions/{sessionId}/runs`：创建一次运行
- `GET /api/v1/runs/{runId}/events`：SSE 推理事件（ReAct v0）
- `GET /api/v1/runs/{runId}`：查询运行状态
- 审批流程：
  - `POST /api/v1/runs/{runId}/approvals`
  - `GET /api/v1/approvals/{approvalId}`
  - `POST /api/v1/approvals/{approvalId}/resolve`
- 模型提供方切换：
  - `agent.llm.provider=mock`：规则/Mock 路径（默认）
  - `agent.llm.provider=deepseek`：真实模型调用路径（M4，基于 LangChain4j）
- 工具系统（M5）：
  - `GET /api/v1/tools`：查看工具清单
  - `POST /api/v1/tools/{toolName}/invoke`：调试调用工具
  - 工具清单包含 `riskLevel`（`LOW`/`MEDIUM`/`HIGH`）
  - 在 `prompt` 中可使用 `tool://<toolName> <json>` 触发工具（示例：`tool://echo {"text":"你好"}`）
- 扩展基石（M5+ 第 1 步）：
  - 已引入统一 `ToolProvider` 路由层，当前默认 provider 为 `local`
  - `MCP ToolProvider` 采用 LangChain4j MCP（stdio transport）对接 MCP Server，运行期通过 `listTools/executeTool` 动态发现与执行工具
  - MCP 工具会映射为逻辑名（默认前缀 `mcp.`），并继续受本地风险分级与审批机制约束
  - Skill 提示词注入开关与模板（`agent.skill.*`）已接入推理主链路
  - DeepSeek 路径默认开启 LangChain4j function calling（`agent.llm.function-calling-enabled=true`），由模型自主决定何时调用工具
  - 模型触发工具调用时，会在 SSE 中透传 `tool.invoked` / `tool.result` 事件（`route=MODEL_FUNCTION_CALL`）
  - `tool://<toolName> <json>` 保留为显式调试入口，不影响模型自主调用路径
  - 风险判定最小实现：当 `agent.tool-risk.enabled=true` 且工具风险达到 `agent.tool-risk.block-level`（默认 `HIGH`）时，执行被拦截并返回 `TOOL_APPROVAL_REQUIRED`
  - 风险拦截联动审批：运行期触发高风险工具时，自动创建审批并发出 `approval.requested` 事件（随后本次 run 以 `APPROVAL_REQUIRED` 结束）
  - 审批通过后可重试同一 run：对于 `TOOL_EXECUTE:<toolName>` 类型审批，系统对该工具提供一次性放行（执行后失效）
  - 短期记忆（M7）：按会话维护最近滑窗上下文（`agent.memory.window-size`），在模型调用时注入 `HISTORY`
  - 记忆存储可切换：`agent.memory.provider=in_memory|redis`（默认 `in_memory`，切换 `redis` 后支持跨重启保留）
  - 稳定性增强（M9a）：新增 LLM 熔断（`agent.stability.*`），连续失败达到阈值后短时快速失败，SSE `run.failed.reason` 返回 `CIRCUIT_OPEN`
- RAG 持续迭代（M10a/M10b）：新增 `rag.retrieved` 事件与检索上下文注入，基于 LangChain4j `ContentRetriever/Query/Content` 抽象，`agent.rag.provider` 支持 `mock/local_vector/elastic`
- `local_vector`：使用本地测试文本做向量召回，默认向量模型选型 `text-embedding-3-small`（OpenAI 兼容）；若未配置 embedding 凭据，会自动降级为本地哈希向量，方便先打通链路
- `elastic`：支持通过 HTTP 调用 ES `_search` 做 Top-K 召回，配置缺失时自动降级为空结果

## 测试

```bash
mvn test
```

## M4 大模型配置（DeepSeek）

已预留本地配置文件：`src/main/resources/application-local.yml`。

你只需填写以下字段：

- `agent.llm.provider=deepseek`
- `agent.llm.base-url=https://api.deepseek.com/v1`
- `agent.llm.api-key=<你的Key>`
- `agent.llm.model=deepseek-chat`（或 `deepseek-reasoner`）

本地按 `local` profile 启动：

```bash
cd backend
mvn spring-boot:run "-Dspring-boot.run.profiles=local"
```

实现说明：当前 DeepSeek 通过 `LangChain4j OpenAiChatModel` 接入，使用 OpenAI 兼容接口（`base-url` 一般为 `https://api.deepseek.com/v1`）。
当 `agent.llm.function-calling-enabled=true` 时，会通过 LangChain4j 的 tool calling 能力调用本地工具桥接层。

## 基础设施连接配置预留（MySQL / Redis / Elasticsearch）

已新增模板文件：`src/main/resources/application-local-infra.template.yml`，用于预留数据库与检索系统连接参数（不会被 Spring 自动加载）。

建议做法：

1. 复制模板为 `application-local-infra.yml`
2. 按本机环境填写 MySQL / Redis / ES 参数
3. 将其内容合并到 `application-local.yml`（或后续通过 profile include 引入）

推荐当前阶段先使用 `agent.rag.provider=local_vector` 进行本地 RAG 联调，待有真实知识数据后再切换到 `elastic`。

## MCP（stdio）接入方式

- `agent.mcp.enabled=true`
- `agent.mcp.tool-prefix="mcp."`
- `agent.mcp.stdio.enabled=true`
- `agent.mcp.stdio.command=<你的 MCP Server 启动命令>`
- `agent.mcp.stdio.args=[...]`
- `agent.mcp.stdio.env={...}`

推荐本地配置：

- 优先接入一个只读 MCP 工具集做联调，避免一次性暴露高风险工具
- 将高风险工具（如 delete/remove）交由风险拦截 + 人工审批链路兜底

## 说明

- 统一错误体见 `ApiErrorBody`，与 `docs/api/openapi.yaml` 对齐。
- CORS 已允许 `http://localhost:*`，便于前端开发。
