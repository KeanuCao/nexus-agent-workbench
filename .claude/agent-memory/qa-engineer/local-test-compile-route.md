---
name: local-test-compile-route
description: 宿主没有 mvn 时，如何本地编译并运行后端单测（JDK17 + .tmp 的 jar 缓存 + javac argfile + junit console standalone）
metadata:
  type: project
---

**结论：本项目可以在宿主上自编自跑后端单测，不必等 builder**（builder 容器不常驻、宿主无 mvn）。

**Why**：交付单测时"编不过就别交"是硬要求，而等用户跑 `mvn -B test` 的反馈循环太长（几分钟 + 一次往返）。
2026-09-22 写阶段3 的 6 个 RAG 单测时验证了这条路线：**59 条用例 4 秒跑完**（首次编译约 30 秒）。

**How to apply（照抄即可）**：
- JDK：宿主 `javac`/`java` 是 Temurin **17**（`/c/Program Files/Eclipse Adoptium/jdk-17.0.20.101-hotspot/bin`）。
- jar 缓存：`.tmp/backend-engineer/jars/`（34 个：spring / mybatis-plus / tika / pdfbox / jackson / slf4j / jakarta）。
  缺的测试框架自行下载到 `.tmp/qa-test-jars/`（**Maven Central 可达**）：`junit-platform-console-standalone-1.10.2`
  + `mockito-core-5.11.0` + `byte-buddy(-agent)-1.14.18` + `objenesis-3.3`。
- 编译：`javac @args`（argfile 里给 `-encoding UTF-8`、`-d <out>`、`-sourcepath <各模块 src/main/java>`、`-cp <jars;>`、目标测试文件）。
  ⚠️ **`-encoding UTF-8` 必须加**：宿主 javac 默认 GBK，中文源码会报 `unmappable character`。
  `-sourcepath` 让 javac 隐式编译被引用的生产类（不必手写 classpath 的模块顺序）。
- 运行：`java -Dfile.encoding=UTF-8 -jar .tmp/qa-test-jars/junit-platform-console-standalone-1.10.2.jar execute @run.args`
  （run.args 里给 `--class-path`（out + jars + `src/test/resources`）、`--scan-class-path`、`--details=tree`）。
- 包内可见的测试缝（如 `TextChunker.chunk(text,size,overlap)`、`TikaDocumentParser.normalize`、`OllamaEmbeddingService.readJson`）
  要求测试类的包名与生产类一致，否则编不过。
- `.tmp/` 是 gitignored、随时代删 —— 里面**没有**长期资产，jar 没了要重下。
- ⚠️ 同源教训：`$` 经 `wsl -d … -- bash -c "…"` **双层包装会被吃掉**（实测：`${kb}` 与 `$((kb*1000))` 变空，
  症状是"for 循环跑了但变量全空"，而 `$( )` 有时又没事）—— 要么写无 `$` 的命令，要么落成脚本文件执行。

相关：[[wc-wsl-dollar-trap]]（未写）、[[nginx-1mb-body-wall]]。
