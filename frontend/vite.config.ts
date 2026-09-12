import { fileURLToPath, URL } from 'node:url'

import vue from '@vitejs/plugin-vue'
import { defineConfig } from 'vite'

/**
 * Vite 配置 —— 依据 docs/design/00-环境与部署.md §5.4
 * 两处必须与既有交付物保持一致，改动前先核对：
 *   ① server.proxy：dev 代理 /api → 后端 8089，让 Windows 侧 `npm run dev` 直接打通全链路（§5.4 验收）
 *   ② build.outDir：产物必须是默认的 dist，docker-compose/frontend/Dockerfile 以
 *      `COPY --from=build /app/dist /usr/share/nginx/html` 取产物（构建上下文为 frontend/）
 */
export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      // 与 tsconfig.json 的 compilerOptions.paths["@/*"] 保持一致
      '@': fileURLToPath(new URL('./src', import.meta.url))
    }
  },
  server: {
    port: 5173,
    proxy: {
      '/api': {
        // 后端容器在 WSL 内映射到宿主 8089；Windows 侧 dev server 经此代理访问
        target: 'http://localhost:8089',
        changeOrigin: true
        // 刻意不做 rewrite：后端接口自带 /api 前缀（§5.3 契约为 GET /api/health），
        // 与生产环境 nginx 反代（location /api/ → proxy_pass http://nexus-backend:8089）行为一致
      }
    }
  },
  build: {
    outDir: 'dist'
  }
})
