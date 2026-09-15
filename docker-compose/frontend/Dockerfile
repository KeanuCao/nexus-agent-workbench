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
# ⚠️ 失败形态与后端不同（**2026-09-15 实测订正**：原注释写「全部 404」是推演，实测不成立）。
#    产物缺失、且**后端在跑**时，nginx 照常启动，状态码为：
#      GET /            → 403（`directory index of "/artifacts/frontend/" is forbidden`：目录在、index.html 不在、autoindex 关）
#      GET /<其他路径>  → 404（含 /index.html。2026-09-15 给 nginx.conf 的 try_files 补了 `=404`；
#                          补之前是 500 —— 回退目标 index.html 自己也不存在时，nginx 会内部重定向死循环）
#    容器 healthcheck 打 / 得 403 → **unhealthy**（这一条原注释说对了）。
#    ⚠️ 级联（**已知行为，刻意不修**）：若此时后端**不在运行**（例如它自己也在缺产物的崩溃循环里），
#       nginx 会在**启动期** `[emerg] host not found in upstream "nexus-backend"` 而**启动失败**，
#       前端跟着进入 Restarting 循环 —— 因为 proxy_pass 的 upstream 是启动时一次性解析的，
#       而崩溃中的容器在 Docker 内嵌 DNS 里没有记录。
#       **为什么保留**：这让"前端依赖后端"这件事以最响亮的方式暴露（前端不会静静起来、然后每个 API 请求 502）。
#       代价是排查时容易误判成 nginx 配置问题 —— 记住顺序：**先看后端在不在，再看前端**。
#    排查时别把 403/404 当成 nginx 配置写错（配置没错，是还没构建）。
# =============================================================================
FROM docker.m.daocloud.io/library/nginx:1.27-alpine

# 默认站点配置即反代配置（root 指向共享卷里的前端产物）
COPY nginx.conf /etc/nginx/conf.d/default.conf

EXPOSE 80
