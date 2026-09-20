package com.nexus.module.ai.gateway.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/**
 * 对话请求体（契约：{@code docs/api/README.md} §6.1）。
 *
 * <p><b>无状态设计</b>（决策 D9）：多轮上下文由前端持有 {@code messages} 数组、每次全量上送，
 * 后端不落库、不建会话表 —— 代价是刷新页面即丢上下文，由前端给用户一句提示。
 *
 * <p>{@code modelType} <b>刻意用 {@code String} 而不是 {@code ModelType} 枚举</b>：
 * 若声明为枚举，Jackson 遇到未知取值会抛 {@code HttpMessageNotReadableException}
 * → 落全局兜底 → <b>500 + 50000</b>，而不是契约要的 <b>200 + 10200</b>。
 * 用 String 承接、由 {@code ModelType.parse()} 显式转换，错误码才归得对。
 * （该异常如今也有了自己的出口 —— 见 {@code GlobalExceptionHandler} —— 但那只是兜底，
 * 正解仍是让业务层显式解析。）
 *
 * @author nexus
 */
public class ChatRequest {

    /** 会话正文总长的字节上限（64KB，UTF-8 计），契约见 {@code docs/api/README.md} §6.1 第 3 条。 */
    public static final int MAX_TOTAL_BYTES = 64 * 1024;

    /** 会话历史全量（至少 1 条，最后一条必须是 {@code role=user}）。 */
    @Valid
    @NotEmpty(message = "messages 不能为空")
    private List<ChatMessage> messages;

    /**
     * 模型类型：{@code OLLAMA} / {@code DEEPSEEK}。
     *
     * <p><b>可空</b>（决策 D6）：{@code null} 或缺省 = 由后端决定，本轮解析为
     * {@code nexus.ai.default-model}。这是"契约向后兼容"的落点 ——
     * 将来上线自动路由时，前端只需在下拉里加一个"自动"选项。
     * 不做 {@code @Pattern} 之类的格式校验：取值合法性由 {@code ModelType.parse()} 判定，
     * 它要抛的是业务码 10200，而不是笼统的参数不合法 40001。
     */
    private String modelType;

    /**
     * 最后一条必须是用户提问。
     *
     * <p>为什么要有这条：两个上游都是"接着最后一条继续生成"，若最后一条是
     * {@code assistant}，模型会把"续写助手的话"当成任务，表现为答非所问。
     * 这是契约里明写的约束（§6.1 字段表），与其让上游给出难解释的结果，不如在这里挡掉。
     *
     * <p>不做"角色必须交替"的校验：前端回滚该轮 assistant 消息后会留下连续两条 {@code user}
     * —— 契约明确允许（§6.1 补充约束 1），两个上游也都接受。
     *
     * @return {@code true} 表示最后一条是 {@code role=user}
     */
    @AssertTrue(message = "messages 的最后一条必须是 role=user")
    public boolean isLastMessageFromUser() {
        if (messages == null || messages.isEmpty()) {
            // 空数组交给 @NotEmpty，不在两个地方报同一个错
            return true;
        }
        ChatMessage last = messages.get(messages.size() - 1);
        if (last == null || last.getRole() == null) {
            // role 缺失交给 ChatMessage 的 @NotBlank，同一处不报两遍
            return true;
        }
        return ChatMessage.ROLE_USER.equals(last.getRole());
    }

    /**
     * 会话正文总长校验（UTF-8 字节数累计）。
     *
     * @return {@code true} 表示未超限
     */
    @AssertTrue(message = "会话正文总长不能超过 64KB（UTF-8 字节数）")
    public boolean isTotalContentSizeWithinLimit() {
        if (messages == null) {
            return true;
        }
        int total = 0;
        for (ChatMessage message : messages) {
            if (message == null) {
                continue;
            }
            total += ChatMessage.utf8Length(message.getContent());
            if (total > MAX_TOTAL_BYTES) {
                // 提前返回：超大请求（如误贴了一整本书）不该被完整扫一遍再判
                return false;
            }
        }
        return true;
    }

    public List<ChatMessage> getMessages() {
        return messages;
    }

    public void setMessages(List<ChatMessage> messages) {
        this.messages = messages;
    }

    public String getModelType() {
        return modelType;
    }

    public void setModelType(String modelType) {
        this.modelType = modelType;
    }

    /**
     * 只输出条数与模型类型，不输出正文（理由同 {@code ChatMessage#toString}）。
     *
     * @return 简要描述
     */
    @Override
    public String toString() {
        return "ChatRequest{messages=" + (messages == null ? 0 : messages.size())
                + " 条, modelType='" + modelType + "'}";
    }
}
