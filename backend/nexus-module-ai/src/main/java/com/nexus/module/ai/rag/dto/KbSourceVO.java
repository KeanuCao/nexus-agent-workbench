package com.nexus.module.ai.rag.dto;

import com.nexus.module.ai.rag.model.ChunkHit;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 一条引用片段（契约：{@code docs/api/README.md} §7.6）。
 *
 * <p>{@link #content} 是<b>分块原文</b>：引用即原文 —— 不截断、不摘要。这是"答案可当场核对"
 * 的前提（设计 §4.9 第 3 条）：面试里被问"你这是不是编的"，翻出这一段原文就能回答。
 * 也因此它属于<b>用户上传的外部文本</b>，前端必须插值渲染（{@code {{ }}} / {@code v-text}），
 * <b>绝不用 {@code v-html}</b>（设计 §5.1-3 的 XSS 坑）。
 *
 * @param documentId 来源文档 ID
 * @param fileName   来源文件名（展示用；该文件并不落盘）
 * @param chunkIndex 该分块在文档内的序号，<b>0 起</b> ⇒ 展示「第 N 段」时用 {@code chunkIndex + 1}
 * @param score      余弦相似度 = {@code 1 - (embedding <=> query)}，<b>保留 4 位小数</b>
 *                   （见 {@link #from(ChunkHit)}；取值域 {@code [-1, 1]}）
 * @param content    分块原文
 * @author nexus
 */
public record KbSourceVO(
        long documentId,
        String fileName,
        int chunkIndex,
        double score,
        String content) {

    /** 相似度的展示精度（契约 §7.6：保留 4 位小数）。 */
    private static final int SCORE_SCALE = 4;

    /**
     * 由检索命中的一行转换。
     *
     * <p><b>为什么在这里做四舍五入</b>：{@code score} 由 SQL 算出（{@code 1 - (embedding <=> ?)}），
     * 那是个 {@code double} —— 直接发给前端就可能看到 {@code 0.8240999999999999} 这种值：
     * 契约写着保留 4 位，而"让前端自己 format"等于把契约里的显示规则交给每个调用方各实现一遍。
     * 用 {@link BigDecimal} 而不是 {@code Math.round(score * 10000) / 10000.0}：前者的十进制
     * 语义是明确的（{@code 0.82405} 这类边界值按 {@code HALF_UP} 四舍五入），
     * 后者的中间结果会落在二进制浮点上，边界值的取向要靠推。
     *
     * <p>⚠️ <b>只影响回给前端的值，不影响排序</b>：引用片段的顺序由 SQL 的
     * {@code ORDER BY embedding <=> ?} 决定（越相似越靠前），这里不做二次排序
     * （重排会让"展示的顺序"与"数据库的距离顺序"分家，而后者才是可解释的）。
     *
     * @param hit 检索命中行
     * @return 契约形状的引用片段
     */
    public static KbSourceVO from(ChunkHit hit) {
        return new KbSourceVO(
                hit.documentId(),
                hit.fileName(),
                hit.chunkIndex(),
                roundScore(hit.score()),
                hit.content());
    }

    /**
     * 按契约的精度四舍五入一个相似度（{@code public} 是为了让日志也用同一份口径 ——
     * {@code KbAskServiceImpl} 的检索日志里 {@code maxScore} / {@code threshold} 与响应里的
     * {@code score} 用同一个函数格式化，两处读数就不会出现"日志 0.82411544 / 响应 0.8241"
     * 这种需要解释的差异）。
     *
     * @param score 原始相似度
     * @return 保留 4 位小数后的值
     */
    public static double roundScore(double score) {
        return BigDecimal.valueOf(score).setScale(SCORE_SCALE, RoundingMode.HALF_UP).doubleValue();
    }
}
