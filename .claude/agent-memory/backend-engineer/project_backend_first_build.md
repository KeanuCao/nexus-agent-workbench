---
name: backend-first-build-transient-truncation
description: nexus-backend 首次镜像构建失败（Maven 传输截断）已由重跑验证为瞬时故障；既定处置是不改 POM、不加 maven.test.skip
metadata:
  type: project
---

nexus-backend 镜像**首次**构建（2026-09-11）在 `nexus-start` 模块失败：`Premature end of Content-Length delimited message body (expected 586,031 / received 524,288)`，下载 `junit-jupiter-params-5.10.5.jar` 时被截断。报错行含 `from/to aliyun-central` → 阿里云源注入是生效的，属**传输层**失败而非配置层。

**用户重跑后构建通过**（2026-09-11，用户口头确认）→ 判定为**瞬时传输截断**，此前"512KB 整数边界指向确定性分块"的备择假设被证伪。

**Why:** 该事件的价值在于"源配好 ≠ 下载成功"——失败模式清单必须把传输层错误（截断/超时）与配置层错误并列，且处置路径不同（重试 vs 改配置）。

**How to apply:** 再遇同类截断/超时报错，先判定"配置层还是传输层"（看日志里是哪个 repo），传输层一律先重跑（BuildKit cache mount 会保留已下载内容，重试成本≈补齐失败的那一个文件）。**既定结论：不为这类抖动改动后端 POM、不给 Dockerfile 加 `-Dmaven.test.skip=true`**（它省不掉 test 依赖解析，见 [[maven-test-skip-does-not-skip-resolution]]）。`nexus-start` 保留 `spring-boot-starter-test` 是 TC-00 的正当需求，勿删。Dockerfile 里给 mvn 加有界重试循环属**未采纳的可选加固**，归 devops 领地。
