# nexus-module-ai

AI 核心模块：**统一 AI 网关 / RAG 知识库 / Agent 编排**。阶段0 为空壳模块，阶段2-4 填充。

## 阶段0 交付范围

- 仅包结构占位（`com.nexus.module.ai`，见 `src/main/java/.../package-info.java`）与依赖链路；
- **不预置任何 AI SDK / HTTP 客户端依赖** —— 阶段2 选型（Spring AI 或 OkHttp + 策略模式）
  与版本号以彼时实际存在的版本为准，避免此刻臆测（设计文档 §6 风险 7）。

## 依赖

- `nexus-common`、`nexus-infrastructure`（版本由父 POM 统一管理）
- 禁止反向依赖，禁止与 `nexus-module-system` 平级互依

## 阶段计划内容

| 阶段 | 包 | 内容 |
| --- | --- | --- |
| 阶段2 | `gateway` | 统一 AI 网关：工厂 + 策略模式切换 Ollama / DeepSeek，SSE 流式输出（`/api/chat/stream`） |
| 阶段3 | `rag` | 文档解析（Tika）→ 分块 → `nomic-embed-text` 向量化 → pgvector 检索 TopK → 拼接 Prompt |
| 阶段4 | `agent` | ReAct 工具调用、多 Agent 串行协作（`CompletableFuture`） |

日志要求：调用大模型、向量入库等关键流程必须打 `log.info`（CLAUDE.md 宪法约束 —— 面试时展示调用链路）。
