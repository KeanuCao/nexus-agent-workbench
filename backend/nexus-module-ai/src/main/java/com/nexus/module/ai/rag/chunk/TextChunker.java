package com.nexus.module.ai.rag.chunk;

import com.nexus.common.exception.SystemException;
import com.nexus.module.ai.rag.config.RagProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 固定窗口分块（决策 D3）：把归一化后的正文切成 {@code List<String>}，<b>顺序即 {@code chunk_index}</b>（0 起）。
 *
 * <h2>五条规则（设计 §4.4，与 {@link #chunk(String, int, int)} 的代码一一对应）</h2>
 * <ol>
 *     <li>输入是<b>已归一化</b>的文本（{@code TikaDocumentParser} 的产出），输出每项即一个分块；</li>
 *     <li>步长 = {@code chunk-size - chunk-overlap}（默认 450）；窗口不足 {@code chunk-size} 时取到末尾；</li>
 *     <li><b>尾块合并</b>：最后一块长度 ≤ {@code chunk-overlap} 时并入前一块（避免一个几十字的尾巴）；</li>
 *     <li>文本长度 ≤ {@code chunk-size} 时<b>产出 1 块</b>（不特判成 0 块 —— 短文档同样要能问答）；</li>
 *     <li>空文本（trim 后为空）<b>不由本类处理</b>：那是 {@code 10203}，在解析层就拦掉了
 *         （本类对空串返回空列表，调用方（{@code KbDocumentServiceImpl}）据此兜底 ——
 *         两个出口都在明处，不做"悄悄补一块空内容"这种事）。</li>
 * </ol>
 *
 * <h2>不做语义分块</h2>
 * 不按句 / 段 / 标题切，也不做递归切分（{@code RAG建议.md} 明确本轮不做）。
 * <b>已知代价</b>：边界会切在句子中间（设计 §10.3）——"分块太大检索不准、太小丢上下文"
 * 正是要留到面试里讲的调参经历，不是待修的缺陷。
 *
 * <h2>计数口径：{@code String.length()}（UTF-16 单元）</h2>
 * 中文 BMP 字符与"字数"1:1；emoji 等增补平面字符按 2 计 —— <b>已知偏差</b>，不为此引入 code point
 * 计数（演示文档里几乎不出现，而换成 code point 会让"500 字"这个配置值与列表页的
 * {@code charCount} 口径分家）。
 *
 * <h2>为什么核心逻辑是<b>包内可见的静态方法</b></h2>
 * 同 {@code OllamaService.parseStream} 的取舍：单测直接调 {@code chunk(text, 500, 50)} 断言块数与重叠，
 * 不必起 Spring 上下文、不必 mock 配置对象。{@link #chunk(String)} 只是"从配置取参数"的那一层，
 * 它不含任何逻辑，也就没有必须被单测覆盖的分支。
 *
 * <p>⚠️ <b>改 {@code chunk-size} / {@code chunk-overlap} 之后必须重传文档</b>（决策 D8：本轮不保存
 * 原始文件，无法对已入库的文档重新分块）—— 症状是"改了配置、块数却没变"，极易被误判成配置没生效。
 *
 * @author nexus
 */
@Component
public class TextChunker {

    private static final Logger log = LoggerFactory.getLogger(TextChunker.class);

    private final RagProperties ragProperties;

    /**
     * @param ragProperties RAG 配置（本类只用 {@code chunk-size} / {@code chunk-overlap} 两个键）
     */
    public TextChunker(RagProperties ragProperties) {
        this.ragProperties = ragProperties;
        // 启动期校验是**有意的 fail-fast**（同 AiProperties 的 default-model：配置写错就该现在响）：
        // 配错的 step（overlap ≥ size）会让第一个上传请求在死循环里烧掉一个请求线程，
        // 那种现象要排查很久才想到配置；放在启动期则是一条带原因的中文消息。
        // 每次调用仍会再校验一遍 —— RagProperties 是可变对象，"启动时合法"不等于"调用时合法"
        validate(ragProperties.getChunkSize(), ragProperties.getChunkOverlap());
        log.info("TextChunker 就绪：chunkSize={} chunkOverlap={}",
                ragProperties.getChunkSize(), ragProperties.getChunkOverlap());
    }

    /**
     * 按配置分块（{@code nexus.ai.rag.chunk-size} / {@code chunk-overlap}）。
     *
     * @param text 归一化后的正文
     * @return 分块列表，顺序即 {@code chunk_index}；输入为空时返回空列表
     * @throws SystemException 配置非法（见 {@link #chunk(String, int, int)}）
     */
    public List<String> chunk(String text) {
        return chunk(text, ragProperties.getChunkSize(), ragProperties.getChunkOverlap());
    }

    /**
     * 固定窗口 + 重叠的核心切分（纯函数，不依赖 Spring）。
     *
     * @param text    归一化后的正文，可为 {@code null} / 空串（返回空列表）
     * @param size    窗口大小（字符），必须 &gt; 0
     * @param overlap 相邻窗口的重叠（字符），必须满足 {@code 0 ≤ overlap < size}
     * @return 分块列表；每块长度 ∈ ({@code overlap}, {@code size}]
     * @throws SystemException 参数非法 —— 属<b>配置/调用方缺陷</b>，不是用户问题（故不抛
     *                         {@code BusinessException}）。这条护栏防的是 {@code step ≤ 0} 时
     *                         <b>死循环</b>：一个配错的值会让请求线程永远转下去，
     *                         那比一条明确的 50000 难排查得多
     */
    static List<String> chunk(String text, int size, int overlap) {
        validate(size, overlap);
        if (text == null || text.isEmpty()) {
            return List.of();
        }

        int length = text.length();
        int step = size - overlap;
        List<String> chunks = new ArrayList<>();

        int start = 0;
        while (start < length) {
            // 窗口不足 size 时取到末尾（规则 2）—— 这一步同时是循环的出口判据：
            // end == length 说明本窗口已覆盖到文末，再按 step 前进只会空转
            int end = Math.min(start + size, length);
            chunks.add(text.substring(start, end));
            if (end == length) {
                break;
            }
            // step > 0 由 validate 保证，故 start 严格递增，循环必然结束
            start += step;
        }

        return mergeShortTail(chunks, overlap);
    }

    /**
     * 尾块合并（规则 3）：最后一块长度 ≤ {@code overlap} 时并入前一块。
     *
     * <p>⚠️ <b>反直觉但成立</b>：在"步长 = size - overlap"这个前提下，本条件<b>永远不成立</b>
     * —— 设最后一块起点为 {@code s}，则 {@code s} 上一轮必然满足 {@code s - step + size < length}
     * （否则上一轮就已取到末尾、循环在那里结束了），代入 {@code step = size - overlap} 得
     * {@code length - s > overlap}，即尾块长度恒大于 {@code overlap}。举实测形态：500/50 时尾块
     * 要么是满窗 500，要么 ∈ (50, 500]（最短的一档是总长 501 ⇒ 尾块 51）。
     *
     * <p>那为什么还留着？① 它是设计写明的规则，且<b>改成按句/按段切分后立刻会生效</b>
     * （设计 §10.3）—— 那时尾块真的可能短到只有几个字；② 它把"最后一块不可能是碎片"这条
     * 不变量表达在代码里，而不是靠上面那段推导口口相传。
     *
     * <p>合并取<b>拼接</b>（而不是"丢掉最后一块"）：两者在本条件成立时内容等价（此时尾块必然是
     * 前一块的子串），拼接不会因为将来语义分块的改动而悄悄丢内容。
     *
     * @param chunks  已切好的块（会被就地修改）
     * @param overlap 重叠大小
     * @return 合并后的块列表
     */
    private static List<String> mergeShortTail(List<String> chunks, int overlap) {
        if (chunks.size() < 2) {
            // 只有一块时不存在"并入前一块"这个动作：短文档必须留下它（规则 4）
            return chunks;
        }
        int lastIndex = chunks.size() - 1;
        String last = chunks.get(lastIndex);
        if (last.length() > overlap) {
            return chunks;
        }
        chunks.remove(lastIndex);
        int previousIndex = chunks.size() - 1;
        chunks.set(previousIndex, chunks.get(previousIndex) + last);
        return chunks;
    }

    /**
     * 参数护栏：防死循环（{@code step ≤ 0}）与无意义的窗口。
     *
     * <p>校验放在<b>每次调用</b>（而不只在构造期）：{@code RagProperties} 是可变对象，
     * 构造期校验过不代表调用期仍然合法。
     *
     * @param size    窗口大小
     * @param overlap 重叠大小
     * @throws SystemException 参数非法
     */
    private static void validate(int size, int overlap) {
        if (size <= 0) {
            throw new SystemException("chunk-size 必须大于 0，当前值：" + size);
        }
        if (overlap < 0) {
            throw new SystemException("chunk-overlap 不能为负数，当前值：" + overlap);
        }
        if (overlap >= size) {
            // 这条最要紧：step = size - overlap ≤ 0 会让窗口原地踏步甚至倒退 ⇒ 死循环。
            // 抛系统异常（50000）而不是业务异常：配错的人是部署者，不是上传文件的用户
            throw new SystemException("chunk-overlap 必须小于 chunk-size（否则步长不是正数、切分会死循环），"
                    + "当前值：chunk-size=" + size + " chunk-overlap=" + overlap);
        }
    }
}
