---
name: project-builder-git-sync-branch-source
description: builder 构建的真源是【容器 env NEXUS_REPO_BRANCH 指向的远端分支】（默认 main），不是宿主当前分支 —— push 功能分支不改 git-sync 结果
metadata:
  type: project
---

`git-sync` 的动作是 `fetch --prune origin` + `checkout --force --detach "origin/${NEXUS_REPO_BRANCH}"`，
而 `NEXUS_REPO_BRANCH` 来自 `docker-compose/.env`（默认 `main`），在 **`docker compose up` 时注入容器 env**。
`db-patch-migrate` 扫的补丁目录 `PATCH_DIR=/workspace/repo/db-patch` 也在**容器内那份 clone** 里。

**Why:** 2026-09-22 阶段2 前预检实测到的一次**近失**：
宿主开发在 `stage0-env-and-skeleton`（HEAD `df11ffb`，6 个未推送提交，含新增补丁 `202609221100`），
但运行中的 `nexus-builder` 容器实测 `NEXUS_REPO_BRANCH=main`、工作区停在 `55b5b89`、
`/workspace/repo/db-patch/` **只有 3 个补丁、没有 `202609221100`**。
⇒ 若只把**功能分支**推上去而没合并到 main，`git-sync` 仍会 checkout 旧的 `origin/main`：
迁移变成"什么都没做"（不报错！），后端也按旧口径（nomic/768）重建 —— 与"忘了 push"是同一种失败的不同入口。

**How to apply:** 要构建「尚未合并到 main 的分支」有两条路，二选一、别含糊：
① 合并功能分支 → push **main**（`.env` 默认就是 main，零配置漂移，推荐）；
② 临时把 `.env` 的 `NEXUS_REPO_BRANCH` 改成分支名，并 **recreate** builder（`docker compose up -d builder` 会因 env 变化重建），
   用完**记得改回 main** —— 忘了改回就会让后续所有构建静默地构建那个旧分支（同 [[builder-m2-persistent-volume-hazard]] 的"静默劣化"味道）。
判据：`git-sync` 输出的提交号应等于**你预期的那个**；若仍是同步前的旧 hash（本例 `55b5b89`），就是 no-op 签名。
读容器真源：`docker inspect nexus-builder | grep -E 'NEXUS_REPO_(URL|BRANCH|DIR)='`
（⚠️ `docker inspect` 的 Env 项是 `"KEY=value"` 形态，grep 模式别在键名后写右引号，否则一条都匹配不到 —— 实测踩过）。
相关：[[project-compose-build-path-resolution]]、[[project-wsl-tooling-facts]]。
