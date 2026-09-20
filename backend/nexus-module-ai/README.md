# nexus-module-ai

AI 核心模块：**统一 AI 网关 / RAG 知识库 / Agent 编排**。阶段0 为空壳模块，阶段2 起按 `gateway` 包填充。

## 阶段状态

- **阶段0**：仅包结构占位（`com.nexus.module.ai`，见 `src/main/java/.../package-info.java`）与依赖链路，
  不预置任何 AI SDK / HTTP 客户端依赖（避免版本臆测，设计文档 §6 风险 7）。该状态**已被阶段2 部分取代**。
- **阶段2**（代码已落地，设计：`docs/design/02-统一AI网关.md`）：`gateway` 包完工。
  - 公共层：端口 `AiModelService`（回调式 `stream(...)`）、`ModelRouter` / `AiModelRegistry` /
    `AiModelFactory`、`dto/*`、`config/AiProperties`、`config/AsyncConfig`；
  - provider（策略实现）：`impl/OllamaService`（NDJSON）、`impl/DeepSeekService`（OpenAI 风格 SSE）
    —— <b>两家上游的行协议差异全部消化在这两个类内部</b>，出口统一成 `Chunk`；
  - 编排与出口：`impl/ChatServiceImpl`（`impl/ChatStreamSession` 持有单条流的状态机）、
    `controller/ChatController`（`POST /api/chat/stream`）。
  ⚠️ 首次编译由 devops 的 `build-backend` 完成（宿主无 `mvn`）；契约见 `docs/api/README.md` §6，
  验收用例见 `docs/test-cases/TC-02.md`。

## 依赖

- `nexus-common`、`nexus-infrastructure`（版本由父 POM 统一管理）
- 禁止反向依赖，禁止与 `nexus-module-system` 平级互依
- 阶段2 新增（选型即决策 D1/D2，**不引 Spring AI、不引 OkHttp**）：
  `spring-web`（RestClient）+ `spring-webmvc`（`SseEmitter` —— 它不在 spring-web 里，
  也不会经 nexus-infrastructure 传递过来）+ `jackson-databind`（解析上游的 NDJSON / SSE 行）
  + `spring-boot-starter-validation`（`@Valid` 注解与运行期实现）
  + `spring-boot-starter-test`(test)。逐条理由见 `pom.xml` 头部注释。

## 阶段计划内容

| 阶段 | 包 | 内容 |
| --- | --- | --- |
| 阶段2 | `gateway` | 统一 AI 网关：工厂 + 策略模式切换 Ollama / DeepSeek，SSE 流式输出（`/api/chat/stream`） |
| 阶段3 | `rag` | 文档解析（Tika）→ 分块 → `nomic-embed-text` 向量化 → pgvector 检索 TopK → 拼接 Prompt |
| 阶段4 | `agent` | ReAct 工具调用、多 Agent 串行协作（`CompletableFuture`） |

日志要求：调用大模型、向量入库等关键流程必须打 `log.info`（CLAUDE.md 宪法约束 —— 面试时展示调用链路）。
