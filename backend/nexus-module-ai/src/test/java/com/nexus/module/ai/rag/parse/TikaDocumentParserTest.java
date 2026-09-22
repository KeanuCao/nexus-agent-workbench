package com.nexus.module.ai.rag.parse;

import com.nexus.common.exception.BusinessException;
import com.nexus.common.result.ResultCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TikaDocumentParser} 单元测试 —— <b>本阶段"机器能钉住"的两件事：编码与类型判定</b>（设计 §7.1）。
 *
 * <h2>为什么这两件值得单测</h2>
 * <ol>
 *     <li><b>GBK 文件按 UTF-8 解 = 一屏乱码</b>（决策 D13 的由来）：它是"静默入库一堆垃圾、
 *         检索时才被发现"的形态 —— 没有异常、没有警告，只有一份答不对的知识库。
 *         本测试断言的是"GBK 能被正确解码"这条**正路**，以及"两种编码都解不像 → 10203"这条负路；</li>
 *     <li><b>类型判定（扩展名 + 内容双重）</b>：只看扩展名时改个后缀就能骗过（决策 D12）。
 *         负路（{@code 10201} / {@code 10203}）各有明确的用户动作，混起来会把排查方向带偏 ——
 *         契约 §7.7 把它们分成两格，单测就必须把它们分得一样清楚。</li>
 * </ol>
 *
 * <p><b>为什么能直接调包内可见的 {@code normalize(...)}</b>：它是 TXT 与 PDF 共用的那一层
 * （见 {@code TikaDocumentParser} 的类注释），三条归一化规则各自有明确目的；
 * 直接喂样本比构造一份"行尾带空格、含连续空行"的 PDF 便宜得多，而断言的语义完全相同。
 *
 * <p>真实连通性（一份 1.6MB 的中文年报、80~90 秒的上传）交给 TC-03 的实机用例 —— 单测不碰网络、
 * 不碰数据库、毫秒级。
 *
 * @author nexus
 */
class TikaDocumentParserTest {

    /** 被解析的 PDF 探针（{@code src/test/resources/rag/sample.pdf}，自己生成的 ASCII 最小 PDF）。 */
    private static final String SAMPLE_PDF = "/rag/sample.pdf";

    /** 最小 PDF 里的可判定文本（见 {@code sample.pdf} 的正文，断言只认这一句）。 */
    private static final String SAMPLE_PDF_TEXT = "Nexus KB sample 2026";

    /** GBK 字符集（与实现里那份 {@code Charset.forName("GBK")} 同源；JDK 没有 StandardCharsets 常量）。 */
    private static final Charset CHARSET_GBK = Charset.forName("GBK");

    /** 替换字符（U+FFFD）：出现它通常意味着"这份文本是按错编码解出来的"。 */
    private static final String REPLACEMENT_CHAR = "�";

    private final TikaDocumentParser parser = new TikaDocumentParser();

    @Test
    @DisplayName("UTF-8 TXT：直读成功，路径标 utf-8，charCount 与正文长度一致")
    void shouldParseUtf8Text() {
        String text = "第一行：知识库说明\n第二行：营业收入 897 亿元";
        byte[] content = text.getBytes(StandardCharsets.UTF_8);

        ParsedDocument parsed = parser.parse(content, "知识库说明.txt");

        assertEquals(text, parsed.text());
        assertEquals("TXT", parsed.fileType(), "fileType 是大写，与 KbDocumentVO.fileType 同口径");
        assertEquals("utf-8", parsed.parserPath());
        assertEquals(text.length(), parsed.charCount(), "charCount 是 String.length() 口径");
    }

    @Test
    @DisplayName("★ GBK TXT 必须被正确解码（决策 D13 ——「乱码」那个坑的机器判据）")
    void shouldDecodeGbkTextWithoutMojibake() {
        String text = "中文编码测试：营业收入 897 亿元，归母净利润 84.1 亿元。";
        byte[] gbkBytes = text.getBytes(CHARSET_GBK);

        ParsedDocument parsed = parser.parse(gbkBytes, "gbk编码.txt");

        assertEquals(text, parsed.text(), "GBK 文件必须按 GBK 解回原文，而不是按 UTF-8 解出一屏替换字符");
        assertEquals("gbk", parsed.parserPath(), "走了 GBK 重解这条路，parser 标识必须如实反映（它只进日志）");
        assertFalse(parsed.text().contains(REPLACEMENT_CHAR), "正文里不该出现任何 U+FFFD");
        assertEquals("TXT", parsed.fileType());
    }

    @Test
    @DisplayName("合法的少量 U+FFFD 不该触发 GBK 重解（1% 阈值不是 0%）")
    void shouldKeepUtf8WhenReplacementCharsAreRare() {
        // 200 字符里 1 个替换字符 = 0.5% < 1%：源文档本身就带脏字符是合法形态，不该被误判成编码错
        String text = "a".repeat(199) + REPLACEMENT_CHAR;
        byte[] content = text.getBytes(StandardCharsets.UTF_8);

        ParsedDocument parsed = parser.parse(content, "脏字符.txt");

        assertEquals("utf-8", parsed.parserPath());
        assertTrue(parsed.text().contains(REPLACEMENT_CHAR), "个别脏字符应原样保留，而不是被当成编码错误拒收");
    }

    @Test
    @DisplayName("★ TXT 可被 GBK 解、但按 UTF-8 是乱码：判据是「解回原文」，不是「没有异常」")
    void shouldNotSilentlyAcceptMojibake() {
        String text = "报告期内公司实现营业总收入 897 亿元。";
        byte[] gbkBytes = text.getBytes(CHARSET_GBK);

        // 先把"按 UTF-8 解会得到什么"记下来：那正是我们**不**希望被入库的形态
        String asUtf8 = new String(gbkBytes, StandardCharsets.UTF_8);
        assertFalse(asUtf8.equals(text), "前提检查：GBK 字节按 UTF-8 解必然与原文不同（否则本用例无意义）");

        ParsedDocument parsed = parser.parse(gbkBytes, "报告.txt");

        assertEquals(text, parsed.text());
        assertFalse(parsed.text().equals(asUtf8), "绝不能把「按 UTF-8 解出来的乱码」当成解析结果收下");
    }

    @Test
    @DisplayName("★ 最小 PDF：Tika 抽出正文（PDF 这条路径真的通）")
    void shouldExtractTextFromPdf() {
        byte[] content = readResource(SAMPLE_PDF);

        ParsedDocument parsed = parser.parse(content, "sample.pdf");

        assertTrue(parsed.text().contains(SAMPLE_PDF_TEXT),
                "抽出的正文里应含 PDF 里的那句话，实际：" + parsed.text());
        assertEquals("PDF", parsed.fileType());
        assertEquals("tika", parsed.parserPath());
        assertTrue(parsed.charCount() > 0);
    }

    @Test
    @DisplayName("扩展名大小写不敏感（契约 §7.1：.PDF 与 .pdf 同等对待）")
    void shouldAcceptUpperCaseExtension() {
        ParsedDocument fromPdf = parser.parse(readResource(SAMPLE_PDF), "SAMPLE.PDF");
        ParsedDocument fromTxt = parser.parse("大小写不敏感".getBytes(StandardCharsets.UTF_8), "NOTE.TXT");

        assertEquals("PDF", fromPdf.fileType());
        assertEquals("TXT", fromTxt.fileType());
        assertEquals("大小写不敏感", fromTxt.text());
    }

    @Test
    @DisplayName("空文件 → 10203（不是 10201：扩展名没问题，问题是没有内容）")
    void shouldRejectEmptyFileWith10203() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> parser.parse(new byte[0], "空文件.txt"));

        assertEquals(ResultCode.KB_PARSE_EMPTY.getCode(), ex.getCode());
    }

    @Test
    @DisplayName("纯空白 TXT → 10203（归一化后仍是空白，同扫描版 PDF 的归宿）")
    void shouldRejectWhitespaceOnlyTextWith10203() {
        byte[] content = "   \n\n\t  \n".getBytes(StandardCharsets.UTF_8);

        BusinessException ex = assertThrows(BusinessException.class, () -> parser.parse(content, "空白.txt"));

        assertEquals(ResultCode.KB_PARSE_EMPTY.getCode(), ex.getCode());
    }

    @Test
    @DisplayName("★ 二进制垃圾改了 .txt 后缀 → 10203（UTF-8 与 GBK 都解不像，不许静默入库）")
    void shouldRejectUnrecognizableBytesWith10203() {
        byte[] garbage = new byte[100];
        for (int i = 0; i < garbage.length; i++) {
            // 0xFF 在 UTF-8 与 GBK 里都是非法字节（实测：两种解码各得 100 个 U+FFFD ⇒ 占比 100%）
            garbage[i] = (byte) 0xFF;
        }

        BusinessException ex = assertThrows(BusinessException.class, () -> parser.parse(garbage, "伪装.txt"));

        assertEquals(ResultCode.KB_PARSE_EMPTY.getCode(), ex.getCode());
    }

    @Test
    @DisplayName(".docx → 10201（Word 本轮不支持，白名单是常量 —— 见设计 §10.1）")
    void shouldRejectDocxWith10201() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> parser.parse("随便什么内容".getBytes(StandardCharsets.UTF_8), "会议纪要.docx"));

        assertEquals(ResultCode.KB_FILE_TYPE_UNSUPPORTED.getCode(), ex.getCode());
    }

    @Test
    @DisplayName("无扩展名 → 10201")
    void shouldRejectMissingExtensionWith10201() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> parser.parse("没有扩展名".getBytes(StandardCharsets.UTF_8), "README"));

        assertEquals(ResultCode.KB_FILE_TYPE_UNSUPPORTED.getCode(), ex.getCode());
    }

    @Test
    @DisplayName("★ 内容与扩展名不符：文本文件改名成 .pdf → 10201（决策 D12 的内容交叉校验）")
    void shouldRejectPlainTextDisguisedAsPdfWith10201() {
        byte[] content = "This is plain text, not a PDF at all.".getBytes(StandardCharsets.UTF_8);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> parser.parse(content, "伪装.pdf"));

        assertEquals(ResultCode.KB_FILE_TYPE_UNSUPPORTED.getCode(), ex.getCode());
    }

    @Test
    @DisplayName("归一化：统一换行为 \\n、去行尾空白、连续空行压到 2 个（只判空，不 trim 整体）")
    void shouldNormalizeLineEndingsAndBlankRuns() {
        assertEquals("甲\n乙\n丙", TikaDocumentParser.normalize("甲\r\n乙\r丙"));
        assertEquals("甲\n乙", TikaDocumentParser.normalize("甲   \n乙\t "));
        assertEquals("甲\n\n\n乙", TikaDocumentParser.normalize("甲\n\n\n\n\n乙"),
                "连续 4 个空行（5 个换行）应压成 2 个空行（3 个换行）");
        assertEquals("  甲", TikaDocumentParser.normalize("  甲"),
                "行首空格**不**去（只 strip 行尾）：多做的每一件美化都会改变 embedding 的输入");
        assertTrue(TikaDocumentParser.normalize("   \n \t ").isBlank(),
                "全空白的输入归一化后仍空白 —— 由调用方判 10203");
    }

    /** 读类路径资源（Maven 与本地 javac 运行都必须把 {@code src/test/resources} 放进 classpath）。 */
    private static byte[] readResource(String path) {
        try (InputStream input = TikaDocumentParserTest.class.getResourceAsStream(path)) {
            assertNotNull(input, "测试资源不存在：" + path);
            return input.readAllBytes();
        } catch (IOException ex) {
            throw new IllegalStateException("读取测试资源失败：" + path, ex);
        }
    }
}
