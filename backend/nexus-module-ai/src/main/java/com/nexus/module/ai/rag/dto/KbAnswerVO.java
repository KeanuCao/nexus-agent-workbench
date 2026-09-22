package com.nexus.module.ai.rag.dto;

import java.util.List;

/**
 * 知识库问答响应（契约：{@code docs/api/README.md} §7.6）。
 *
 * <h2>{@code grounded=false} 时三条规则同时成立，而且只能同时成立</h2>
 * <ol>
 *     <li>{@code answer} = {@link #NO_RESULT_ANSWER}（后端常量，前端不必自己拼）；</li>
 *     <li>{@code sources} = <b>空数组</b>（不是 {@code null} —— 前端可以直接 {@code .length} / {@code .map}）；</li>
 *     <li>{@code generation} = <b>{@code null}</b>（压根没调大模型，所以没有可报的数字）。</li>
 * </ol>
 * 这三条是"检索不到就说不知道"（决策 D9 第二道闸）在<b>响应形状</b>上的投影。
 * {@link #noResult(int, double, long)} 是它们唯一的构造入口 —— 不让调用方逐字段拼，
 * 是因为"漏设其中一个"（典型：{@code generation} 忘设成 null）会让前端显示一个
 * 看起来成功、实际没答的响应，而那正是设计 §3.7 点名要避免的形态。
 *
 * <h2>两个观测块为什么必须有（{@link Retrieval} / {@link Generation}）</h2>
 * 它们是"答案不对"时<b>唯一可见的定位证据</b>：{@code retrieval.hits=0} 指向检索/阈值，
 * {@code hits>0} 但答案不对则要看 {@code sources[].content} 本身乱不乱（乱 = 解析问题）、
 * 以及 {@code generation.durationMs}（是不是上游截断/超时）。没有这两块，
 * 排查就只剩"猜"（设计 §4.9 第 4 条）。
 *
 * <p>两个嵌套 record 不另建文件：契约里它们就是<b>内联的匿名对象</b>（§7.6 的 {@code retrieval} /
 * {@code generation} 都没有独立 schema 名），而它们又只在 {@code KbAnswerVO} 里出现 ——
 * 拆成两个文件只是多两个"只有一处使用"的类。
 *
 * @param answer     模型生成的答案；{@code grounded=false} 时是固定文案
 * @param grounded   答案是否基于检索到的资料。{@code false} = 阈值筛完后 0 条 ⇒ <b>未调用大模型</b>
 * @param sources    引用片段，按 {@code score} 降序；{@code grounded=false} 时为空数组
 * @param retrieval  检索侧观测值
 * @param generation 生成侧观测值；{@code grounded=false} 时为 {@code null}
 * @author nexus
 */
public record KbAnswerVO(
        String answer,
        boolean grounded,
        List<KbSourceVO> sources,
        Retrieval retrieval,
        Generation generation) {

    /**
     * 检索为空时的固定文案（契约 §7.6 逐字）。
     *
     * <p>放在后端而不是前端：它是<b>接口的一部分</b>（契约把这段字写进了响应字段说明），
     * 前端只需原样展示 {@code answer}。放在前端拼的话，两个前端（或以后的小程序）就得各拼一遍，
     * 而"这句提示到底说了什么"会变成两处需要同步的东西。
     */
    public static final String NO_RESULT_ANSWER = "知识库中未找到相关内容，请换一种问法，或先上传相关文档。";

    /**
     * 构造"检索不到"的响应（见类注释的第三条规则）。
     *
     * @param topK       本次生效的 topK
     * @param threshold  本次生效的阈值
     * @param durationMs 检索耗时（<b>含问题向量化</b>，契约 §7.6 的口径）
     * @return {@code grounded=false} 的响应
     */
    public static KbAnswerVO noResult(int topK, double threshold, long durationMs) {
        return new KbAnswerVO(
                NO_RESULT_ANSWER,
                false,
                // 空数组而不是 null：契约明写（前端少一处判空）
                List.of(),
                new Retrieval(topK, 0, threshold, durationMs),
                // 没调大模型 ⇒ 没有生成侧观测值。刻意不用"零值对象"（durationMs=0）：
                // 那会让"没调"与"调了但耗时为 0"在响应里无法区分
                null);
    }

    /**
     * 检索侧观测值。
     *
     * @param topK       本次生效的 topK（请求里带的就是它，没带则是配置值）
     * @param hits       过相似度阈值的条数（= {@code sources.size()}；为 0 时 {@code grounded=false}）
     * @param threshold  本次生效的相似度阈值（{@code nexus.ai.rag.score-threshold}）
     * @param durationMs 检索耗时（<b>含问题的向量化</b> —— 那一步是检索的固有成本，拆开报反而
     *                   会让"检索为什么这么慢"少一半信息）
     * @author nexus
     */
    public record Retrieval(int topK, int hits, double threshold, long durationMs) {
    }

    /**
     * 生成侧观测值。
     *
     * @param modelType  实际使用的模型类型（枚举名，如 {@code DEEPSEEK}）—— 由
     *                   {@code nexus.ai.rag.answer-model-type} 决定
     * @param model      上游真实模型名（如 {@code deepseek-chat} / {@code qwen2.5:7b}）：
     *                   配置只选了"哪家"，真正答话的是这个名字
     * @param durationMs 生成耗时（毫秒）
     * @author nexus
     */
    public record Generation(String modelType, String model, long durationMs) {
    }
}
