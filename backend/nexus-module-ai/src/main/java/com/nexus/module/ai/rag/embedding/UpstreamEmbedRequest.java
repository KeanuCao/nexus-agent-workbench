package com.nexus.module.ai.rag.embedding;

import java.util.List;

/**
 * 发给 Ollama embedding 接口的请求体（{@code {"model":"...","input":[...]}}）。
 *
 * <p>与 {@code gateway.impl.UpstreamChatRequest} 同一条纪律：<b>上行的报文自己描述</b>，
 * 不借用任何入参 DTO（那些类身上挂着校验注解与 {@code isXxx()} 方法，Jackson 会把它们一并
 * 序列化成多余字段发给上游）。
 *
 * <p>刻意与 {@code OllamaEmbeddingService} 分文件、且是<b>包内可见</b>：包内可见是刻意的
 * ——这个类型只服务于本包的实现，不该成为别人能 import 的 API；独立成文件则沿用本仓库
 * 已验证有效的形态（{@code UpstreamChatRequest} 就是这么用的），
 * 不把"Jackson 能否序列化一个私有嵌套 record"这个未知数引进来。
 *
 * <p><b>字段名即上游协议的一部分</b>：{@code input} 是数组（✅ 2026-09-22 探针 B 实测批量入参被接受），
 * 与旧的 {@code /api/embeddings} 端点的 {@code prompt:<string>} 形状不同 —— 那条退路若启用，
 * 本类要一起改（连同 {@code OllamaEmbeddingService} 里的批量循环），端口签名不受影响。
 *
 * @param model 上游真实模型名（2026-09-22 晚起为 {@code bge-m3}；此前是 {@code nomic-embed-text}）
 * @param input 待向量化的文本（实现里<b>可能</b>加任务前缀，见决策 D14 的机制 ——
 *              当前模型不需要前缀，那一侧配置默认是空串，此时就是原文）
 * @author nexus
 */
record UpstreamEmbedRequest(String model, List<String> input) {
}
