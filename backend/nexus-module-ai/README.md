# nexus-module-ai

AI 核心模块：**统一 AI 网关 / RAG 知识库 / Agent 编排**。阶段0 为空壳模块，阶段2 起按 `gateway` 包填充，
阶段3 起再加 `rag` 包。

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
- **阶段3**（设计：`docs/design/03-RAG知识库.md`）：`rag` 包**地基已就位、业务类待落地**。
  - **已落地（本段的交付物）**：三处依赖增量（见下）、`application.yml` 的 `nexus.ai.rag.*` /
    `nexus.ai.ollama.embed-path` / `spring.servlet.multipart.*` / rag mapper debug、
    `ResultCode` +5 个码、`GlobalExceptionHandler` +4 个出口、
    `db-patch/202609221000_初始化知识库表.sql`（两张表 + 4 个索引，含 HNSW）、
    契约 `docs/api/README.md` §7 + `openapi.yaml` 的 4 个 path。
  - **待落地（下一段，设计 §4.1 的文件清单）**：`rag` 子包
    `config`（`RagProperties`）/ `controller`（`KbDocumentController`、`KbAskController`）/
    `dto`（`KbAskRequest`、`KbDocumentVO`、`KbDocumentListVO`、`KbAnswerVO`、`KbSourceVO`）/
    `entity`（`KbDocument`、`KbChunk`）/ `mapper`（`KbDocumentMapper`、`KbChunkMapper`、
    `VectorTypeHandler`）/ `parse`（`DocumentParser` + `TikaDocumentParser`）/ `chunk`（`TextChunker`）/
    `embedding`（`EmbeddingService` + `OllamaEmbeddingService`）/ `prompt`（`PromptBuilder`）/
    `generate`（`ModelAnswerGenerator`）/ `service` + `service/impl`。
  - **依赖方向（模块内也是单向的）**：`rag → gateway`（RAG 复用阶段2 的模型端口生成答案），
    **反过来不成立** —— `gateway` 里任何类都不得 import `rag` 的东西。
    判据：`grep -rn "module.ai.rag" backend/nexus-module-ai/src/main/java/com/nexus/module/ai/gateway/` 必须无输出。
  - 契约见 `docs/api/README.md` §7（四个接口 + 失败形态总表 + 错误码增量），
    实现结构见设计 §4，用例见 `docs/test-cases/TC-03.md`（qa-engineer 产出）。

## 依赖

- `nexus-common`、`nexus-infrastructure`（版本由父 POM 统一管理）
- 禁止反向依赖，禁止与 `nexus-module-system` 平级互依
- 阶段2 新增（选型即决策 D1/D2，**不引 Spring AI、不引 OkHttp**）：
  `spring-web`（RestClient）+ `spring-webmvc`（`SseEmitter` —— 它不在 spring-web 里，
  也不会经 nexus-infrastructure 传递过来）+ `jackson-databind`（解析上游的 NDJSON / SSE 行）
  + `spring-boot-starter-validation`（`@Valid` 注解与运行期实现）
  + `spring-boot-starter-test`(test)。逐条理由见 `pom.xml` 头部注释。
- 阶段3 新增（版本统一在父 POM 的 `dependencyManagement`，本模块不写 `<version>`）：
  - `org.apache.tika:tika-core`（compile，`3.2.3`）：`AutoDetectParser` / `BodyContentHandler` /
    `Metadata` / `TikaException` —— **只用于 PDF**。TXT 走 JDK 直读 + 编码探测（决策 D13），
    因为 TXT 真正的坑是编码（GBK 文件按 UTF-8 解 = 一屏乱码），框架不替我们解决；
  - `org.apache.tika:tika-parser-pdf-module`（**runtime**）：PDF 解析器实现（含 PDFBox），
    代码不 import 它的任何类型，靠 Tika 的服务加载机制在运行期发现；
  - `com.baomidou:mybatis-plus-spring-boot3-starter`（compile）：`BaseMapper` / `@TableName` /
    `@TableId`。它已由 `nexus-infrastructure` 传递进来，显式声明是为了遵循本模块
    "自己 import 的类型自己声明"的既定原则。
  - 版本核实与回退链（Tika 若在镜像源上不存在会**响亮报错**）见父 POM 的 `tika.version` 注释。

## 阶段计划内容

| 阶段 | 包 | 内容 |
| --- | --- | --- |
| 阶段2 | `gateway` | 统一 AI 网关：工厂 + 策略模式切换 Ollama / DeepSeek，SSE 流式输出（`/api/chat/stream`） |
| 阶段3 | `rag` | 文档解析（TXT 直读 + 编码探测 / PDF 走 Tika）→ 固定窗口分块（500/50）→ `nomic-embed-text` 向量化 → pgvector 检索 TopK（HNSW）→ 拼 Prompt → 复用 `gateway` 生成答案 |
| 阶段4 | `agent` | ReAct 工具调用、多 Agent 串行协作（`CompletableFuture`） |

日志要求：调用大模型、向量入库等关键流程必须打 `log.info`（CLAUDE.md 宪法约束 —— 面试时展示调用链路）。
