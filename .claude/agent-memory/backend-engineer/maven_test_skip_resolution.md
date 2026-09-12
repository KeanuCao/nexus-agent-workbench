---
name: maven-test-skip-does-not-skip-resolution
description: Maven 执行器级预解析使 -Dmaven.test.skip=true 无法省掉 test 依赖下载；判据是那行不带 goal 坐标的报错
metadata:
  type: reference
---

`-Dmaven.test.skip=true` **不能**阻止 test 作用域依赖被解析/下载。原因是 Maven 的依赖解析由 mojo 描述符的 `requiresDependencyResolution` 驱动（`compiler:testCompile`、`surefire:test` 均声明 TEST），由 `MojoExecutor` 在**mojo 执行体之前**完成；而 `skip` / `maven.test.skip` 是 mojo 内部参数，判断发生在解析之后。`-DskipTests` 只跳过 surefire 执行，test 源码仍编译。

**How to apply:** 判断一次失败是"执行前预解析失败"还是"mojo 执行失败"，看报错行**是否带 goal 坐标**——`Failed to execute goal on project X: Could not resolve dependencies for project X`（不带 goal，且依赖标注 `(test)`）即执行器级预解析，此时任何 skip 参数都拦不住。

**Why:** 这决定了"能不能用 skip 参数让镜像构建摆脱 JUnit 可用性"这类决策的答案是否定的——唯一确定性手段是把依赖收进 profile（如 `-Pwith-tests`），代价是测试命令必须显式带 profile。**尚未实测**（本机无 Maven，且 backend-engineer 红线不许打包）；自证法：用该参数跑一次，`grep "junit-jupiter-params" 构建日志`，仍出现 `Downloading` 即证实。

相关：[[backend-first-build-transient-truncation]]
