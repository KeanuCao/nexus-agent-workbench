package com.nexus.module.ai.gateway.impl;

import com.nexus.module.ai.gateway.ModelDescriptor;
import com.nexus.module.ai.gateway.dto.ChatMessage;

import java.util.ArrayList;
import java.util.List;

/**
 * 发给上游模型的请求体（{@code model} / {@code messages} / {@code stream} 三个键）。
 *
 * <h2>为什么两个 provider 共用这一个结构</h2>
 * 设计 §4.3 的表格里，Ollama 与 DeepSeek 的<b>请求体形状恰好一致</b>（两家都收
 * {@code {"model":..., "messages":[{"role","content"}],"stream":true}}）：
 * 这是"OpenAI 兼容约定"带来的巧合，不是可以互相推导的规律。共享它仅仅是为了省掉两份逐字相同的
 * 十几行，并把"请求体长什么样"收敛成一个能被单测钉住的点。
 *
 * <p>⚠️ 真正需要各自为政的是<b>行协议</b>（NDJSON vs SSE），那部分仍然完整地待在各自的
 * provider 里 —— 本类只负责"发出去的那一坨 JSON"。将来某一家要加自己的私有参数
 * （Ollama 的 {@code options}、DeepSeek 的 {@code temperature}），那时就该把它拆回去，
 * 而不是往这里加一个只有一家用的字段。
 *
 * <h2>为什么不用现成的 {@code ChatMessage}</h2>
 * {@code ChatMessage} 是<b>入参</b> DTO，身上挂着 {@code isContentSizeWithinLimit()} 之类
 * 供 Bean Validation 用的 {@code isXxx()} 方法 —— Jackson 会把它们一并序列化成
 * {@code contentSizeWithinLimit} 之类的多余字段发给上游。上行的报文自己描述，
 * 两个方向不共用同一个类。
 *
 * @param model    上游真实模型名（{@code qwen2.5:7b} / {@code deepseek-chat}）
 * @param messages 会话历史全量（由 {@link #of(ModelDescriptor, List)} 从入参转换而来）
 * @param stream   是否流式。<b>恒为 {@code true}</b>：本网关只实现流式对话
 *                 （契约 §6.1 的接口名就叫 {@code /api/chat/stream}）
 * @author nexus
 */
record UpstreamChatRequest(String model, List<UpstreamMessage> messages, boolean stream) {

    /**
     * 一条上行消息。
     *
     * <p>只保留协议里真正存在的两个字段：角色与正文。校验相关的字段（长度、最后一条是不是
     * {@code user}）都是本服务侧的规矩，上游不认识它们。
     *
     * @param role    角色（{@code user} / {@code assistant}，契约 §6.1 规定了本阶段只有这两个）
     * @param content 正文
     * @author nexus
     */
    record UpstreamMessage(String role, String content) {
    }

    /**
     * 按本次选定的模型与会话历史构造请求体。
     *
     * <p>模型名取自 {@link ModelDescriptor#modelName()} 而不是重新读一遍配置：
     * {@code meta} 帧回给前端的、日志里打印的、真正发给上游的，必须是<b>同一个字符串</b>
     * （验收标准 2.2-1 判的就是"传 OLLAMA / DEEPSEEK 分别命中对应实现"，三处不一致会让它失真）。
     *
     * @param descriptor 本次选定的模型自述
     * @param messages   会话历史（调用方已校验：非空、最后一条为 {@code user}）
     * @return 上游请求体
     */
    static UpstreamChatRequest of(ModelDescriptor descriptor, List<ChatMessage> messages) {
        List<UpstreamMessage> upstreamMessages = new ArrayList<>(messages.size());
        for (ChatMessage message : messages) {
            upstreamMessages.add(new UpstreamMessage(message.getRole(), message.getContent()));
        }
        return new UpstreamChatRequest(descriptor.modelName(), upstreamMessages, true);
    }
}
