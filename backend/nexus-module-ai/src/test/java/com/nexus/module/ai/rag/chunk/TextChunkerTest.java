package com.nexus.module.ai.rag.chunk;

import com.nexus.common.exception.SystemException;
import com.nexus.module.ai.rag.config.RagProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TextChunker} 单元测试 —— <b>固定窗口分块是"喂样本断言输出"里最划算的一处</b>（设计 §7.1）。
 *
 * <p>它防的缺陷是"块切错了，而检索侧看不出来"：块数少一块、重叠少一个字、窗口多前进一格，
 * 全都<b>不报错</b>，只是答案里少一句上下文。三种判据一起用：
 * <ol>
 *     <li><b>块数与每块长度</b>：直接对齐"步长 = size - overlap"这条规则；</li>
 *     <li><b>相邻块的重叠区</b>：{@code chunks[i+1]} 必须以 {@code chunks[i]} 的最后
 *         {@code overlap} 个字符开头（{@link #shouldOverlapExactly50BetweenAdjacentChunks}）——
 *         重叠写成 0（漏掉重叠）或写成 60（步长算错）都会在这一条上现形；</li>
 *     <li><b>"任何块都不短于 overlap"这条不变量</b>：它是设计 §4.4 规则 3（尾块合并）想要的结果。
 *         ⚠️ 但<b>不</b>为"尾块合并这个分支被触发过"造用例 —— 设计 §4.4 的实施订正写明：
 *         在"步长 = size - overlap"下该分支<b>不可达</b>（7920 组 size × overlap × 长度的网格
 *         穷举触发 0 次），别人为造一个它永远收不到的输入。此处断言的是<b>不变量本身</b>
 *         （将来改成按句切分时它仍然该成立），不是那个分支。</li>
 * </ol>
 *
 * <p><b>为什么直接调 {@code chunk(text, size, overlap)}</b>（包内可见的静态方法，见
 * {@code TextChunker} 的类注释）：这一层不含任何 Spring 依赖，块数与重叠是它的全部语义；
 * 走 {@code new TextChunker(properties).chunk(text)} 那条路只在 {@link #shouldHonorConfiguredChunkParameters()}
 * 里测一次（那是"配置真的被读到了"的判据，不是分块算法的判据）。
 *
 * @author nexus
 */
class TextChunkerTest {

    /** 默认窗口（{@code nexus.ai.rag.chunk-size}）。 */
    private static final int SIZE = 500;

    /** 默认重叠（{@code nexus.ai.rag.chunk-overlap}）。 */
    private static final int OVERLAP = 50;

    /** 步长：两次窗口起点之差。 */
    private static final int STEP = SIZE - OVERLAP;

    @Test
    @DisplayName("空串 / null → 空列表（空文本归 10203，是解析层的事，本类不悄悄补一块）")
    void shouldReturnEmptyListForEmptyText() {
        assertTrue(TextChunker.chunk("", SIZE, OVERLAP).isEmpty(), "空串应产出 0 块");
        assertTrue(TextChunker.chunk(null, SIZE, OVERLAP).isEmpty(), "null 应产出 0 块");
    }

    @Test
    @DisplayName("短文本（< 窗口）→ 1 块，且是全文（短文档同样要能问答）")
    void shouldProduceSingleChunkForShortText() {
        String text = digits(123);

        List<String> chunks = TextChunker.chunk(text, SIZE, OVERLAP);

        assertEquals(1, chunks.size(), "短于窗口的文本必须产出 1 块，不能特判成 0 块");
        assertEquals(text, chunks.get(0));
    }

    @Test
    @DisplayName("恰好 500 → 1 块（边界值不因 off-by-one 多切出一块）")
    void shouldProduceSingleChunkWhenExactlyOneWindow() {
        String text = digits(SIZE);

        List<String> chunks = TextChunker.chunk(text, SIZE, OVERLAP);

        assertEquals(1, chunks.size());
        assertEquals(SIZE, chunks.get(0).length());
        assertEquals(text, chunks.get(0));
    }

    @Test
    @DisplayName("501 → 2 块（第二块从第 450 个字符起，长度 51 —— 尾块不是碎块）")
    void shouldSplitIntoTwoChunksAt501() {
        String text = digits(501);

        List<String> chunks = TextChunker.chunk(text, SIZE, OVERLAP);

        assertEquals(2, chunks.size());
        assertEquals(SIZE, chunks.get(0).length());
        assertEquals(51, chunks.get(1).length());
        // 逐字对齐"块 = text[start, min(start+size, len))"这条定义：位置写错（如从 500 起）会在这里现形
        assertEquals(text.substring(0, SIZE), chunks.get(0));
        assertEquals(text.substring(STEP), chunks.get(1));
        assertTrue(chunks.get(1).length() > OVERLAP, "尾块长度必须大于 overlap（设计 §4.4 的不变量）");
    }

    @Test
    @DisplayName("★ 相邻块的重叠恰好 50：后一块必须以前一块最后 50 个字符开头")
    void shouldOverlapExactly50BetweenAdjacentChunks() {
        String text = digits(1000);

        List<String> chunks = TextChunker.chunk(text, SIZE, OVERLAP);

        assertEquals(3, chunks.size());
        assertEquals(List.of(500, 500, 100), lengths(chunks));
        // 重叠的**观测形态**：相邻两块在边界上共享 50 个字符。
        // 重叠 = 0（漏配）→ 第二块从 500 起，startsWith 立刻失败；步长算错（如 440）→ 同样失败
        assertTrue(chunks.get(1).startsWith(chunks.get(0).substring(STEP)), "第 1 块与第 2 块必须重叠 50 字");
        assertTrue(chunks.get(2).startsWith(chunks.get(1).substring(STEP)), "第 2 块与第 3 块必须重叠 50 字");
        // 反向：重叠必须**恰好** 50，不能是"顺手多带一段"
        assertFalse(chunks.get(1).startsWith(chunks.get(0).substring(STEP - 1)),
                "若第 2 块从第 449 个字符起，说明重叠不是 50");
    }

    @Test
    @DisplayName("中文长文本：同一套窗口规则（计数按 UTF-16 单元，500 汉字 = 500 长度）")
    void shouldChunkChineseTextBySameRules() {
        String text = "中文".repeat(1000);

        List<String> chunks = TextChunker.chunk(text, SIZE, OVERLAP);

        assertEquals(5, chunks.size(), "2000 字按 500/50 切成 5 块（起点 0/450/900/1350/1800）");
        assertEquals(List.of(500, 500, 500, 500, 200), lengths(chunks));
        // 逐块与原文对齐（中文按 UTF-16 单元切，不存在"切半个字"的形态）
        assertEquals(text.substring(0, 500), chunks.get(0));
        assertEquals(text.substring(450, 950), chunks.get(1));
        assertEquals(text.substring(1800), chunks.get(4));
    }

    @Test
    @DisplayName("任意长度都满足不变量：块 ≤ 窗口、覆盖全文，且**多块时**不出现 ≤ overlap 的碎块")
    void shouldKeepInvariantsForAnyLength() {
        int[] lengths = {1, 50, 51, 449, 450, 451, 499, 500, 501, 899, 900, 901, 1000, 1350, 1351, 2000, 2001};

        for (int length : lengths) {
            String text = digits(length);
            List<String> chunks = TextChunker.chunk(text, SIZE, OVERLAP);

            assertFalse(chunks.isEmpty(), "长度 " + length + " 不该切出 0 块");
            for (String chunk : chunks) {
                assertTrue(chunk.length() <= SIZE, "长度 " + length + "：存在超窗块（" + chunk.length() + "）");
                // ⚠️ "尾块 > overlap" 这条只对**多块**成立：单块是规则 4（短文档必须留下一整块，
                //    否则 30 字的说明文档会一块都不剩 → 永远检索不到）。故这里带上 chunks.size() == 1 的豁免
                assertTrue(chunk.length() > OVERLAP || chunks.size() == 1,
                        "长度 " + length + "：多块情形下出现长度 ≤ overlap 的碎块（" + chunk.length() + "）");
            }
            // 首块必从第 0 字起、尾块必到文末：序列整体覆盖全文（顺序即 chunk_index）
            assertTrue(text.startsWith(chunks.get(0)), "长度 " + length + "：首块必须从第 0 字起");
            assertTrue(text.endsWith(chunks.get(chunks.size() - 1)), "长度 " + length + "：尾块必须到文末");
        }
    }

    @Test
    @DisplayName("★ 可配置性：改 chunk-size/chunk-overlap 后同一文本块数随之变多（task.4 3.3 点名的一条）")
    void shouldHonorConfiguredChunkParameters() {
        RagProperties properties = new RagProperties();
        // 同一份 2000 字文本：默认 500/50 → 5 块；改成 200/20（步长 180）→ 11 块
        List<String> defaultChunks = new TextChunker(properties).chunk(digits(2000));

        properties.setChunkSize(200);
        properties.setChunkOverlap(20);
        List<String> tunedChunks = new TextChunker(properties).chunk(digits(2000));

        assertEquals(5, defaultChunks.size());
        assertEquals(11, tunedChunks.size(), "2000 字按 200/20 切：起点 0/180/…/1800 ⇒ 11 块");
        assertEquals(200, tunedChunks.get(0).length());
        assertTrue(tunedChunks.get(1).startsWith(tunedChunks.get(0).substring(180)),
                "步长必须跟着新参数一起变（180 = 200 - 20）");
    }

    @Test
    @DisplayName("★ 参数护栏：overlap ≥ size 必须抛系统异常（不然是死循环，不是慢）")
    void shouldRejectParametersThatWouldLoopForever() {
        String text = digits(1000);

        // size ≤ 0
        assertThrows(SystemException.class, () -> TextChunker.chunk(text, 0, 0));
        // overlap < 0
        assertThrows(SystemException.class, () -> TextChunker.chunk(text, 500, -1));
        // overlap == size（步长 0）
        assertThrows(SystemException.class, () -> TextChunker.chunk(text, 100, 100));
        // overlap > size（步长为负）
        assertThrows(SystemException.class, () -> TextChunker.chunk(text, 100, 120));
    }

    @Test
    @DisplayName("★ 构造期 fail-fast + 调用期复查：配置写错在启动时响，改了属性再调用也会响")
    void shouldFailFastOnInvalidConfiguredParameters() {
        RagProperties properties = new RagProperties();
        properties.setChunkSize(100);
        properties.setChunkOverlap(100);
        assertThrows(SystemException.class, () -> new TextChunker(properties),
                "配错的 chunk-overlap 必须在启动期就喊出来（死循环要排查很久才想到配置）");

        // RagProperties 是可变对象：构造期合法 ≠ 调用期合法，故每次调用还要再校验一遍
        RagProperties mutated = new RagProperties();
        TextChunker chunker = new TextChunker(mutated);
        mutated.setChunkOverlap(600);
        assertThrows(SystemException.class, () -> chunker.chunk(digits(1000)));
    }

    /** 造一段确定性的文本（长度精确可控，且与"分块边界"没有任何巧合）。 */
    private static String digits(int length) {
        StringBuilder builder = new StringBuilder(length);
        while (builder.length() < length) {
            builder.append("0123456789");
        }
        return builder.substring(0, length);
    }

    /** 各块长度（断言形状比断言拼接后的字符串更好读）。 */
    private static List<Integer> lengths(List<String> chunks) {
        return chunks.stream().map(String::length).toList();
    }
}
