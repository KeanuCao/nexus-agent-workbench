# =============================================================================
# nexus-frontend 运行镜像（2026-09-13 按新宪法重建）
#
# 与旧版的根本差别：**镜像里不再包含前端产物**。
#   旧（多阶段构建）：stage 1 以 nexus-builder 跑 npm ci + npm run build，stage 2 把 dist/ COPY 进来。
#   新（产物共享目录）：dist/ 由 **builder 容器**构建后写进命名卷 build-artifacts，
#                       本容器的 nginx 把 root 指向挂载点 /artifacts/frontend。
#
# 因此本镜像 = nginx + 站点配置，构建上下文只需本目录（nginx.conf 就在同目录，直接 COPY）。
#
# ⚠️ 失败形态与后端不同：nginx 不会因为 root 目录为空而拒绝启动，它会正常起来、
#    对所有请求返回 404（容器 healthcheck 随即转为 unhealthy）。
#    所以前端"没产物"表现为 **404 + unhealthy**，而不是像后端那样启动即崩 ——
#    排查时别把 404 当成 nginx 配置写错。
# =============================================================================
FROM docker.m.daocloud.io/library/nginx:1.27-alpine

# 默认站点配置即反代配置（root 指向共享卷里的前端产物）
COPY nginx.conf /etc/nginx/conf.d/default.conf

EXPOSE 80
