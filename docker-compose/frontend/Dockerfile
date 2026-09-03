# =============================================================================
# nexus-frontend 多阶段构建（task.1 / 0.1 交付物）
# 构建上下文：../frontend（0.4 落码后的 Vite 工程；本 Dockerfile 位于 docker-compose/frontend/）
# nginx.conf：与 Dockerfile 同目录，经 compose build.additional_contexts 命名上下文
#             nginx-conf 引入镜像（COPY --from=nginx-conf）
#   - stage 1：共用构建阶段 nexus-builder（node 20 + npm）
#   - stage 2：nginx 1.27-alpine 静态托管 + /api 反代
# =============================================================================

# ── stage 1：npm 构建（npm 全局缓存走 BuildKit cache mount，依赖下载跨构建复用）──
FROM nexus-builder:dev AS build
WORKDIR /app
# 先拷依赖清单：改动源码不破坏依赖层缓存；package-lock.json 由 0.4 生成并提交（npm ci 必需）
COPY package*.json ./
RUN --mount=type=cache,target=/root/.npm npm ci
COPY . .
RUN npm run build

# ── stage 2：nginx 静态托管 + 反代（不含 Node 运行时，体积/攻击面瘦身）──
FROM docker.m.daocloud.io/library/nginx:1.27-alpine
# 来自 named context nginx-conf（指向 docker-compose/frontend/），默认站点配置即反代配置
COPY --from=nginx-conf nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 80
