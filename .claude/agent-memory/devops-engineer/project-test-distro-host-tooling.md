---
name: project-test-distro-host-tooling
description: 测试发行版 nexus-agent-workbench-test **宿主侧**的工具实况（无 java/node/unzip；有 curl/git/python3.12）+ WSL 墙上时钟会跳（计时用 monotonic）
metadata:
  type: project
---

2026-10-10 实测（起因：5.5 要在宿主上找 JVM / 解压工具生成 Allure 报告）：

- **测试发行版宿主没有**：`java` / `javac` / `node` / `npm` / `allure` / `unzip` / `psql`（逐个 `command -v` 验过）。
- **有**：`curl`、`wget`、`git`、`python3` **3.12.3**、`sudo`（`sudo` 组已配）；用户 = `caotan`（**uid 1004**）+ `docker` 组；
  runner 与手动 checkout 都在 `/home/caotan/`（checkout = `~/nexus/nexus-agent-workbench`，初始在 main；
  CI 运行环境 venv = `~/nexus-e2e-venv`）。
- ⇒ 任何"宿主上要跑 JVM/Node 工具"的方案都不成立（要么装，要么**借容器**）—— 借容器时见 [[project-builder-container-tooling]]（builder 镜像有 JDK 17）。
- **zip 解压**：宿主无 `unzip`，但 python3 的 `zipfile` 可用（5.5 的 Allure CLI 分发就是这么解的）。

**WSL 墙上时钟会被宿主拨动（实测）**：同一台机上 `time.time()` 在几秒内出现**倒退**（5.5 首版脚本打出过
"用时 **-0.3s**"）⇒ **任何计时的脚本一律用 `time.monotonic()`**，别用墙上时钟相减。

**How to apply:** 为测试环境写宿主脚本前先对照本页的"有/没有"清单 —— 缺的工具要么绕（python 标准库）、
要么借容器；写耗时统计时直接用 monotonic。宿主验证卷内容的安全手法（不动真卷）见 [[project-report-pipeline-delivery]]。
