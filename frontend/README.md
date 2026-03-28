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

## 当前页面操作（M4）

1. 在输入框输入问题并点击 **发送**
2. 前端会自动：
   - 创建会话（首次）
   - 提交运行（携带 prompt）
   - 订阅 SSE 事件流
3. 在聊天区域查看模型输出（`run.completed.detail.answer`）
4. 若失败会显示 `run.failed` 的原因信息

## 构建

```bash
npm run build
```

产物在 `dist/`，可用于后续静态托管或网关反代。
