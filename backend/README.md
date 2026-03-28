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

## M1 能力

- `POST /api/v1/sessions`：创建会话（内存存储，重启丢失）
- `POST /api/v1/sessions/{sessionId}/runs`：创建一次运行
- `GET /api/v1/runs/{runId}/events`：SSE，推送 **Mock** 事件（不调用 LLM）

## 测试

```bash
mvn test
```

## 说明

- 统一错误体见 `ApiErrorBody`，与 `docs/api/openapi.yaml` 对齐。
- CORS 已允许 `http://localhost:*`，便于前端开发。
