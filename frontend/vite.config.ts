import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

// 开发时通过代理访问本机 Spring Boot（默认 8080），避免 CORS 配置差异。
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      "/api": {
        target: "http://127.0.0.1:8080",
        changeOrigin: true,
      },
    },
  },
});
