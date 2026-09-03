---
name: feedback-docker-runtime-restrictions
description: 未经用户明确批准禁止执行任何真实 docker 操作（up/pull/build/拉模型），只允许 compose config 静态校验
metadata:
  type: feedback
---

规则：本项目中任何真实 docker 操作——`docker compose up`、`docker pull`、`docker compose build`、拉取 Ollama 模型——都必须先等用户逐项检查交付文件并明确发话后才执行。在此之前只允许 `docker compose config` 静态校验（compose 语法 + .env 插值）。

**Why:** 用户明确要求省钱（镜像/模型下载量巨大，Ollama 两模型约 5GB）且倾向人工逐项检查交付文件后再实机验证；task.1 0.1 任务即按此边界执行。

**How to apply:** 每个子任务交付后输出「人工检查清单 + 建议的下一步实机验证命令」，等用户批准再执行；若 compose config 因 docker daemon/工具缺失失败，如实报告"待用户环境验证"，不反复重试、不启动 daemon、不执行任何探测类命令（wsl -l -v、netstat 等）。所有 docker 命令经 `wsl -d nexus-agent-workbench -- bash -c "cd /mnt/c/wp/nexus-agent-workbench && ..."` 执行（仓库在 WSL 内路径 /mnt/c/wp/nexus-agent-workbench）。相关：[[wsl-distro]] [[docker-mirror-rule]]
