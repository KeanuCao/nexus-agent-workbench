package com.nexus.module.ai.rag.parse;

/**
 * 一次解析的结果：纯文本 + 两件"排查质量时第一眼要看的事"（走了哪条路径、有多少字符）。
 *
 * <p><b>为什么把 {@code parserPath} 也带出来</b>：TXT 的 UTF-8 / GBK 两条路径与 PDF 的 Tika 路径
 * 产出的是同一种东西（字符串），但出问题时的排查方向完全不同 —— 这条日志（设计 §4.11 的
 * {@code parser=tika} / {@code parser=gbk}）是"这份文件到底怎么被解出来的"唯一可见证据。
 * 只回一个字符串的话，那个信息在解析器内部就被丢掉了。
 *
 * <p><b>为什么 {@code charCount} 不是 record 组件而是派生方法</b>：它就是
 * {@code text.length()}。存成组件意味着存在两个可能不一致的字段（构造时传错一个数，
 * 与正文对不上的字符数会一路流进 {@code t_kb_document.char_count} 与列表页，
 * 而它恰恰是"解析质量一眼可判"的落点）。派生则不可能漂移。
 *
 * <p>{@code String.length()} 是 <b>UTF-16 单元</b>口径（决策 D3 的计数口径，见
 * {@link com.nexus.module.ai.rag.chunk.TextChunker} 的类注释）：中文 BMP 字符与"字数"1:1，
 * emoji 等增补平面字符按 2 计 —— 已知偏差，不为它引入 code point 计数。
 *
 * @param text       归一化后的正文（非空，见 {@link TikaDocumentParser} 的入参约定）
 * @param fileType   归一化后的文件类型，大写（{@code TXT} / {@code PDF}）—— 即
 *                   {@code t_kb_document.file_type} 与契约 {@code KbDocumentVO.fileType} 的取值。
 *                   由解析器给出而不是让调用方再拆一次扩展名：白名单判定本来就在这里做
 *                   （"谁判定、谁给出"），两处各拆一次就会出现两处对不上的可能
 * @param parserPath 实际走的解析路径：{@code tika}（PDF）/ {@code utf-8} / {@code gbk}（TXT），
 *                   只用于日志
 * @author nexus
 */
public record ParsedDocument(String text, String fileType, String parserPath) {

    /**
     * 正文字符数（{@code String.length()} 口径，见类注释）。
     *
     * @return 字符数，≥ 1（解析层已挡掉空文本）
     */
    public int charCount() {
        return text.length();
    }
}
