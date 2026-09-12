---
name: compose-build-path-resolution
description: compose build 三个相对路径各相对谁解析（2026-09-11 本机实测 compose v5.1.4）+ 唯一能静态抓到路径错误的命令 docker compose build --print
metadata:
  type: project
---

# compose build 相对路径解析基准（2026-09-11 实测：compose v5.1.4 / buildx v0.34.1-desktop.1 / docker 29.5.3）

- `build.context`、`build.additional_contexts` 的本地路径值：相对 **compose 文件所在目录**（`docker compose config` 里二者都显示为绝对路径）。
- `build.dockerfile`：`config` 中**保持原样**（相对就还是相对），拼装发生在 bake 转换阶段，规则是 **相对「已解析的 context」**：
  - `<context>/<dockerfile>`，`..` 会被归一化。实测：`context: ../frontend` + `dockerfile: ./frontend/Dockerfile` → `<repo>/frontend/frontend/Dockerfile`（不存在 → 构建报 `lstat <repo>/frontend/frontend: ...`）。
  - 反例佐证：`builder` 服务 `context: ./builder` + `dockerfile: Dockerfile` → `<repo>/docker-compose/builder/Dockerfile`（存在），所以 builder 能构建成功；若规则是"相对 compose 目录"，它应解析到 `<repo>/docker-compose/Dockerfile`（不存在）而失败。

**Why:** 2026-09-11 用户执行 `docker compose build nexus-frontend` 报 `resolve : lstat /mnt/.../frontend/frontend: no such file or directory`。根因：两条 build 段把 `dockerfile` 基准误当 compose 目录。backend 同病（`backend/backend/Dockerfile`），只是错误只先抛了一条。

**How to apply:** 写/审 compose build 段时，先手算 dockerfile 的最终落点（context 已在 config 里解析成绝对路径，再拼 dockerfile），确保拼出来是真实存在的文件。本项目 Dockerfile 位于 `docker-compose/<svc>/`、context 在仓库应用目录，故需写 `../docker-compose/<svc>/Dockerfile` 才能拼回真文件。

# 静态预检（不触发构建，`config` 抓不到这类错）
`docker compose config` 只做模型归一化：解析了 context/additional_contexts，但**不解析 dockerfile、不做任何存在性检查**，所以放行。改用：
```bash
cd /mnt/c/wp/nexus-agent-workbench/docker-compose
docker compose build --print 2>/dev/null | grep -o '"/[^"]*"' | tr -d '"' | sort -u \
  | xargs -I PATHH sh -c 'test -e PATHH && echo "OK   PATHH" || echo "MISS PATHH"'
```
实测：修前输出 2 条 MISS（backend/backend/Dockerfile、frontend/frontend/Dockerfile）+ 其余 OK；改成 `../docker-compose/<svc>/Dockerfile` 后全 OK。注意 `build --print` 尾部会带一条无害 warning `No services to build`。外网不可达，规则以本机实测为准，见 [[no-internet-verify-empirically]]。
