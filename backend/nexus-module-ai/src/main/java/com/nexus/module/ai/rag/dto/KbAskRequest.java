package com.nexus.module.ai.rag.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 知识库问答请求体（契约：{@code docs/api/README.md} §7.4）。
 *
 * <p>可变 POJO 而非 record：与 {@code ChatMessage} / {@code LoginRequest} 同一条理由 ——
 * <b>入参</b>是 Jackson 反序列化的目标，setter 注入是最不需要额外约定的写法；出参（{@code KbAnswerVO} 等）
 * 反过来用 record。这是本模块 dto 包的既有分工。
 *
 * <h2>两条校验为什么<b>只留了 {@code @NotBlank}</b>，其余在业务侧判</h2>
 * <ol>
 *     <li><b>{@code question} 留 {@code @NotBlank}</b>：它表达的是"这个字段必须有值"，
 *         与框架的默认消息（"不能为空"是我们自己写的中文）都不违背契约 ——
 *         失败出口是 {@code 40001}（{@code MethodArgumentNotValidException} 的既有出口）；</li>
 *     <li><b>{@code topK} 与问题长度<b>不</b>用 {@code @Min/@Max/@Size}</b>：契约要的是
 *         "越界 → {@code 40001} + <b>可读文案</b>"，而 Bean Validation 的默认消息是英文的
 *         （{@code must be less than or equal to 20}），会被全局异常处理器<b>直接弹给用户</b>。
 *         要让它说中文，就得把校验消息一条条重写……那不如把这两条边界挪到业务侧
 *         （{@code KbAskServiceImpl}），那里能给出"topK 取值范围为 1~20"这种带上下文的话，
 *         且码仍是 {@code 40001}。</li>
 * </ol>
 * 两条边界的真源：{@code nexus.ai.rag.max-question-length}（默认 500 <b>字符</b>，不是字节）与
 * {@code nexus.ai.rag.max-top-k}（默认 20）。<b>字符 vs 字节的口径差异是有意的</b>：对话接口的
 * 8KB 字节上限防的是"误贴大段文本撑爆上游上下文"，而问题天然是短的（设计 §3.4 第 2 条）。
 *
 * @author nexus
 */
public class KbAskRequest {

    /** 用户问题：非空、去空白后非空（{@code @NotBlank} 的语义正是"trim 后非空"）。 */
    @NotBlank(message = "问题不能为空")
    private String question;

    /**
     * 取几条资料，可空。
     *
     * <p>用包装类型 {@code Integer}（不是 {@code int}）：契约里"缺省 / {@code null} = 用配置值"
     * 与"显式传 0"是两种不同的输入，{@code int} 会把前者悄悄变成后者（0），
     * 于是"没传"会被当成一次越界。
     */
    private Integer topK;

    public String getQuestion() {
        return question;
    }

    public void setQuestion(String question) {
        this.question = question;
    }

    public Integer getTopK() {
        return topK;
    }

    public void setTopK(Integer topK) {
        this.topK = topK;
    }

    /**
     * 刻意<b>不输出问题正文</b>：它是用户数据，且参数绑定失败时框架可能把它打进调试日志
     * （与 {@code ChatMessage} 不输出 {@code content} 是同一条纪律：隐私 + 日志体积）。
     *
     * @return 只含长度与 topK 的简要描述
     */
    @Override
    public String toString() {
        return "KbAskRequest{questionLength=" + (question == null ? 0 : question.length())
                + ", topK=" + topK + '}';
    }
}
