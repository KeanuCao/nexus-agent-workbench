---
name: builder-m2-persistent-volume-hazard
description: builder-m2 是持久卷 —— 它会静默复用卷里的旧构件，把「配置没生效」伪装成构建成功（版本漂移故障的放大器）
metadata:
  type: project
---

`builder` 容器的本地仓库卷 `builder-m2` 是**持久化**的（非 `run --rm` 的一次性卷）。后果：Maven 会优先命中卷里的旧构件，而不是报错。

**Why:** 2026-09-15 实测的版本漂移故障里，父 POM `0.2.1` / 子模块 `<parent><version>` 写死 `0.2.0`，Maven 在 `../pom.xml` 读到 GAV 不匹配后**回落本地仓库**解析 `nexus-parent:0.2.0`；因为卷里恰好有旧版本，构建**成功且零警告**，产物版本仍是旧的（现场日志 `Building nexus-common 0.2.0` 与 `Building nexus-parent 0.2.1` 并存）。把本地仓库的 `com/nexus` 挪走后才响亮失败（`Non-resolvable parent POM ... 'parent.relativePath' points at wrong local POM`）。教训：**报错比静默安全** —— 持久卷把「配置写错」从红色失败降级成了无声的旧产物。

**How to apply:** 在本项目遇到「改了配置/版本，构建成功但行为或产物没变」时，第一反应应是**怀疑持久卷里的旧构件**，而不是相信 exit code 0。判据要落在产物本身（`Building <模块> <版本>` 日志、`nexus-start/target/*.jar` 的文件名与 mtime、`.flattened-pom.xml` 里的落版值），不能只看「构建成功」。相关：[[project-mirror-daocloud-verified]]、[[project-compose-build-path-resolution]]。
