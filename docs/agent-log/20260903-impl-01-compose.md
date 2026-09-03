# 实现 0.1 基础设施编排

- **Agent**：devops-engineer ｜ **耗时**：约 24 分钟 ｜ **task-id**：af8a5f296a1cc3d9d

## 做了什么
- 交付 `docker-compose/docker-compose.yml`（name: nexus、nexus-net、3 命名卷、7 服务）、`.env`、builder/backend/frontend 三个 Dockerfile、nginx.conf、两个 `.dockerignore`、`.gitignore` 的 `.env` 例外。
- builder 用 `profiles: ["build"]` 隔离，运行期不常驻；Dockerfile 放 compose 目录、构建上下文指向 `../backend`、`../frontend`。
- `docker compose config` 静态校验通过（WSL 内执行）。

## 遇到的坑
- compose `additional_contexts` 的 list-of-maps 写法被当前 compose 版本拒绝（"unexpected type map"），改成 map 写法通过——留了 `COPY --from=nginx-conf` 引入 nginx.conf 的方案说明。
- DaoCloud 官方镜像 `/library/` 路径写法当时未实测，仅按规则直写（后续 Step 1 实测 2.9s 通过）。

## 下次怎么改进
- Dockerfile 在构建上下文之外（`dockerfile:` 指定路径）依赖 BuildKit，classic builder 会报错——0.4 实建时如报错，兜底方案是移入 backend/frontend 目录。
- 交付前自查仓库是否残留上一轮的空目录（本次发现 `dist/`、`docker/nginx/nginx.conf/` 空目录）。
