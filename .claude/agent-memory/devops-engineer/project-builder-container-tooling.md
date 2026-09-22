---
name: project-builder-container-tooling
description: builder 容器（nexus-builder 镜像）里有什么工具——**没有 unzip**，验 jar 内文件要用 JDK 的 `jar xf`；与 WSL 宿主工具清单不是一回事
metadata:
  type: project
---

2026-09-22 实机验证（起因：验"产物 jar 里到底有没有本次修复的类"）：

- **`unzip` 不存在**（`command -v unzip` 空）⇒ `unzip -p app.jar <类路径>` 这条路走不通，别试完再报"验不了"。
- **可用替代（已实测）**：JDK 自带的 `jar` ——
  `cd /tmp/jarx && jar xf "$NEXUS_ARTIFACT_DIR/backend/app.jar" BOOT-INF/classes/com/nexus/start/handler/GlobalExceptionHandler.class`，
  再 `grep -a -o '<中文文案>' 该文件` 即可断言标记串在不在。
  抽出来的 `.class` 的 **mtime = 打包时间**，可当"这是本次构建的产物"的旁证。
- 同次实测可用的还有：`grep -a`、`sha256sum`、`psql`、`git`、`mvn`、`npm`、`ls -l --time-style=full-iso`。

**Why:** 本项目已确认「exit 0 ≠ 产物是新的」（见 [[project-builder-m2-persistent-volume-hazard]]），
所以判据必须落在产物本身；而"验产物"就要求知道**用哪个工具能从 jar 里取内容** —— 不知道就会把
"工具缺失"误当成"证据拿不到"，最后退回只看 exit code。

**How to apply:** 要在 builder 里验 jar 内容，直接走 `jar xf` 抽到 `/tmp` 再 grep。
⚠️ **别把本文件与 [[project-wsl-tooling-facts]] 混起来**：那份讲的是 **WSL 发行版**内的工具（如 jq 缺失），
本文件讲的是 **builder 容器**内的工具 —— 两个环境各有一份"没有的东西"，判据不能互相套用。
镜像重建失败时的「内容是否中立」判据已落仓库共享文档 `wsl.abc.md` **§3.9**，本记忆不重复其内容，只留指针。
