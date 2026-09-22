package com.nexus.module.ai.rag.model;

/**
 * 检索命中的一行：一条分块 + 它所属的文档 + 它这次的相似度得分。
 *
 * <p>它是<b>检索 SQL 与上层服务之间的交接类型</b>（设计 §4.6 的检索语句
 * SELECT 出 {@code chunk_id / document_id / chunk_index / content / char_count / score}），
 * 由 {@code KbChunkMapper.search(...)} 逐行映射而来。<b>本类不映射 {@code embedding} 列</b>
 * —— 检索永不返回向量（只返回正文与算好的得分），这也是实体 {@code KbChunk} 不映射该列的同一条理由
 * （决策 D2 的代价落地处）。
 *
 * <p>检索 SQL 会 JOIN {@code t_kb_document} 取 {@code file_name}（引用要显示来源文件名）——
 * 那个 JOIN 不改变本类的形状，只是让 {@link #fileName} 有值可填。
 *
 * @param documentId 来源文档 ID（{@code t_kb_document.document_id}）
 * @param fileName   来源文件名（{@code t_kb_document.file_name}，仅回显用，本轮不落盘）
 * @param chunkIndex 该分块在文档内的序号，<b>0 起</b>（{@code t_kb_chunk.chunk_index}）——
 *                   展示成"第 N 段"时要 {@code +1}（契约 §7.6 与 {@code PromptBuilder} 都按这个口径）
 * @param content    分块原文（引用即原文：不截断、不摘要，见设计 §4.9 第 3 条）
 * @param charCount  该分块的字符数（{@code t_kb_chunk.char_count}）。
 *                   本轮没有直接消费者（回给前端的引用卡片只有文件名/段号/得分/原文），
 *                   留它是为了两件事：① 与 SQL 选出的行形状一致；② 排查时"这一段有多长"是
 *                   判断"是不是分块切坏了"的第一眼数据
 * @param score      余弦相似度 = {@code 1 - (embedding <=> query)}，<b>由 SQL 算出</b>；
 *                   应用层只做比较与格式化 —— 不要在 Java 里重算一遍距离（设计 §4.9 第 2 条）
 * @author nexus
 */
public record ChunkHit(
        long documentId,
        String fileName,
        int chunkIndex,
        String content,
        int charCount,
        double score) {
}
