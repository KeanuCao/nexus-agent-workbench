/**
 * AI 核心模块：统一 AI 网关、RAG 知识库、Agent 编排（阶段2-4 填充）。
 *
 * <p>规划内的包结构（按阶段创建，见 docs/task/task.3 ~ task.5）：
 * <pre>
 * com.nexus.module.ai
 *  ├── gateway    统一 AI 网关：策略/工厂模式切换本地 Ollama 与云端 DeepSeek（阶段2）
 *  ├── rag        知识库：文档解析、分块、向量化入库、TopK 检索（阶段3）
 *  ├── agent      Agent 编排：ReAct 工具调用、多 Agent 协作（阶段4）
 *  ├── entity     数据库实体
 *  └── model      DTO / VO（含 SSE 流式事件结构）
 * </pre>
 *
 * <p>依赖方向：本模块 → nexus-common + nexus-infrastructure，禁止反向依赖；
 * 也禁止与 nexus-module-system 平级互依。
 *
 * <p>阶段0 状态：空壳模块，占位目的同 nexus-module-system（保证多模块链路可编译、可提交）。
 *
 * @author nexus
 */
package com.nexus.module.ai;
