# Nexus-Java 自主 Agent 框架（仓库根）

## 当前进度（M1）

- **后端**（`backend/`）：Spring Boot，REST + SSE Mock 事件，内存会话/运行，无 MySQL/Redis。
- **前端**（`frontend/`）：Vite + React，创建会话 → 提交任务 → 订阅 SSE。
- **契约**（`docs/api/openapi.yaml`）：与实现对齐的最小 OpenAPI。

## 本地运行（无 Docker）

1. 启动后端（二选一）：
   - `cd backend && mvn spring-boot:run`
   - 或在仓库根目录：`mvn -pl backend spring-boot:run`  
   （勿在根目录执行无 `-pl backend` 的 `spring-boot:run`，详见 `backend/README.md`）
2. 启动前端：`cd frontend && npm install && npm run dev`（默认 **5173**，通过代理访问后端）。

详见各子目录 `README.md`。

## 文档

- 产品：`docs/prd/prd.md`
- 设计：`docs/design/`
- 计划：`docs/plan/work-plan.md`
