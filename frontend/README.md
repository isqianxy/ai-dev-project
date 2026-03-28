# Nexus Agent 前端（React + Vite）

## 环境

- Node.js 20+（LTS 推荐）
- npm 或 pnpm

## 启动（本地）

1. 先启动后端（`backend`，端口 8080）。
2. 在本目录执行：

```bash
npm install
npm run dev
```

浏览器打开 Vite 提示的地址（默认 `http://localhost:5173`）。请求经 `vite.config.ts` 代理到 `http://127.0.0.1:8080/api`。

## M1 页面操作

1. **创建会话**
2. **提交空任务**
3. **订阅 SSE**：控制台展示 Mock 事件流

## 构建

```bash
npm run build
```

产物在 `dist/`，可用于后续静态托管或网关反代。
