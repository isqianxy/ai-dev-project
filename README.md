# Nexus-Java 自主 Agent 框架（仓库根）

## 当前进度（与 `docs/plan/work-plan.md` 同步）

- **已完成里程碑**：M1 骨架 → M2 契约 → M3 ReAct v0 → M4 DeepSeek（LangChain4j）→ M5 工具系统 → M5+（`ToolProvider`、Skill 注入、MCP stdio、Function Calling 与工具 SSE）→ M6 工具风险与审批（含单次放行）→ M7 短期记忆滑窗与 **Redis 可切换** → M8a **traceId** 贯穿 SSE → M9a **LLM 熔断** → M10a/M10b **RAG**（检索事件、上下文注入；ES 配置化及基础设施模板预留）。
- **下一阶段**：M10c（向量检索与强过滤）。
- **原则**：契约先行（OpenAPI）、可测试、安全默认（高风险工具走审批）、主链路优先复用 **LangChain4j**（模型 / 工具 / RAG / 记忆）。

### 能力速览

| 模块 | 说明 |
|------|------|
| 后端 `backend/` | Spring Boot 3、REST + **SSE**（`run.started` / `reasoning.step` / `rag.retrieved` / `tool.*` / `approval.*` / `run.completed` 等）、会话与运行、审批 API |
| 模型 | `agent.llm.provider`：`mock` / `deepseek`（OpenAI 兼容），支持 **Function Calling** |
| 工具 | 本地注册工具 + 可选 **MCP**（stdio）；统一 `ToolProvider` 路由；工具风险分级与审批联动 |
| 记忆 | 滑窗上下文；`agent.memory.provider`：**in_memory** / **redis** |
| RAG | `agent.rag.provider`：**mock** / **local_vector** / **elastic**（可降级，不阻断主链路） |
| 前端 `frontend/` | Vite + React：会话、提交 Run、**SSE 调试面板**（事件过滤、trace、工具列表与调试调用） |
| 契约 | `docs/api/openapi.yaml` 为对外 API 单一事实来源 |

## 本地运行（无 Docker）

1. 启动后端（二选一）：
   - `cd backend && mvn spring-boot:run`
   - 或在仓库根目录：`mvn -pl backend spring-boot:run`  
   （勿在根目录执行无 `-pl backend` 的 `spring-boot:run`，详见 `backend/README.md`）  
   使用 DeepSeek 等真实模型时，建议：`cd backend && mvn spring-boot:run "-Dspring-boot.run.profiles=local"`，并配置 `application-local.yml`。
2. 启动前端：`cd frontend && npm install && npm run dev`（默认 **5173**，通过代理访问后端）。

**细节与配置**（模型 Key、MCP、Redis、ES、基础设施模板等）：见 `backend/README.md` 与 `backend/src/main/resources/application-local-infra.template.yml`。

## 运行效果展示

### 启动页面

![启动页面](docs/images/runtime-startup.png)

### 工作页面

![工作页面](docs/images/runtime-working.png)

### Redis 记忆存储

![Redis 记忆存储](docs/images/runtime-redis-memory.png)

## 文档

- 产品：`docs/prd/prd.md`
- 设计：`docs/design/`
- 计划与里程碑：`docs/plan/work-plan.md`
