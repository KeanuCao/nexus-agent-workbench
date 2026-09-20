package com.nexus.module.ai.gateway.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

import java.nio.charset.StandardCharsets;

/**
 * 一条会话消息（契约：{@code docs/api/README.md} §6.1）。
 *
 * <p>用可变 POJO 而非 record：与 {@code LoginRequest} 同一条理由 —— <b>入参</b>是 Jackson
 * 反序列化的目标，setter 注入是最不需要额外约定的写法；出参（{@code ChatStreamMeta} 等）
 * 反过来用 record（由本项目自己构造，不可变更安全）。这也是本模块内 dto 包的既有分工。
 *
 * <p>本类<b>带校验注解</b>（与 {@code LoginRequest} 刻意不带校验注解正相反）：
 * 登录接口的契约是"失败一律 200 + 10100"，加注解会提前抛成 40001；
 * 而对话接口的契约里本来就有 {@code 40001}（参数不合法），校验失败正是它要覆盖的场景。
 *
 * @author nexus
 */
public class ChatMessage {

    /** 单条正文的字节上限（8KB，UTF-8 计），契约见 {@code docs/api/README.md} §6.1 第 3 条。 */
    public static final int MAX_CONTENT_BYTES = 8 * 1024;

    /** 用户角色（本阶段只支持 user / assistant，不支持 system）。 */
    public static final String ROLE_USER = "user";

    /** 助手角色。 */
    public static final String ROLE_ASSISTANT = "assistant";

    /** 消息角色：{@code user} / {@code assistant}。 */
    @NotBlank(message = "role 不能为空")
    @Pattern(regexp = ROLE_USER + "|" + ROLE_ASSISTANT, message = "role 只能是 user 或 assistant")
    private String role;

    /** 消息正文：非空、去空白后非空（{@code @NotBlank} 的语义正是"trim 后非空"）。 */
    @NotBlank(message = "content 不能为空")
    private String content;

    /**
     * 单条正文长度校验（{@code @AssertTrue} 而不是 {@code @Size}）。
     *
     * <p><b>为什么按字节而不是字符</b>：契约写的 8KB 是字节口径，而一个汉字占 3 字节 ——
     * 用 {@code @Size(max = 8192)} 按字符判，上限会被放宽到三倍，而这条上限防的是
     * "误贴大段文本把上游上下文撑爆"，放宽三倍就防不住了。
     *
     * <p>{@code null} / 空串一律放行：那是 {@code @NotBlank} 的职责，
     * 在这里再报一次只会让同一个错误出现两条校验消息。
     *
     * @return {@code true} 表示未超限
     */
    @AssertTrue(message = "单条消息正文不能超过 8KB（UTF-8 字节数）")
    public boolean isContentSizeWithinLimit() {
        return utf8Length(content) <= MAX_CONTENT_BYTES;
    }

    /**
     * 计算文本的 UTF-8 字节数。
     *
     * <p>放在本类是因为"字节数"只服务于本类的上限校验与 {@code ChatRequest} 的总长校验
     * —— 两处共用同一个实现，避免"单条按字节、总体按字符"这类口径不一致。
     *
     * @param text 文本，可为 {@code null}
     * @return UTF-8 字节数；{@code null} 记 0
     */
    public static int utf8Length(String text) {
        return text == null ? 0 : text.getBytes(StandardCharsets.UTF_8).length;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    /**
     * 刻意不输出 {@code content}：对话正文属用户数据，且可能很长
     * （入参一旦被打进日志——如参数绑定失败的调试日志——就是一次隐私泄漏）。
     * 与 {@code LoginRequest} 不输出 {@code password} 是同一条纪律。
     *
     * @return 只含角色的简要描述
     */
    @Override
    public String toString() {
        return "ChatMessage{role='" + role + "', contentLength=" + utf8Length(content) + "B}";
    }
}
