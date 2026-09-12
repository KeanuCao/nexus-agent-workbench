---
name: no-internet-verify-empirically
description: 本环境（Windows + WSL nexus-agent-workbench）访问外网全部超时/重置、无 proxy，查不了在线文档——只能靠本机实测与 --help 文本验证
metadata:
  type: project
---

# 本环境无外网（2026-09-11 实测）

Windows 侧与 WSL 内 `curl` 访问 `raw.githubusercontent.com`、`docs.docker.com` 均超时（exit 28）或 connection reset；`env` 无 proxy 变量。

**Why:** 2026-09-11 定位 compose 构建报错时，任务要求"给官方文档原文 URL"——实际网络不可达，只能改用本机 compose/buildx 实测取证（好在结论比引文档更贴合本机版本）。

**How to apply:** 涉及"某工具行为规则"的判断，别承诺查文档、别凭记忆把文档当权威：① 优先用 `--help`（如 `docker compose build --help`、`docker buildx bake --help`）确认命令语义；② 设计不触发真实操作的最小实验（如 `-f -` 从 stdin 喂最小 compose + `--print` 类命令）把结论建立在本机输出上；③ 二进制内嵌字符串可用 `grep -a` 查（但格式串可能是拼接的，未必命中）。无法验证的部分要在交付物里明确标注"未验证的推断"。相关：[[compose-build-path-resolution]]
