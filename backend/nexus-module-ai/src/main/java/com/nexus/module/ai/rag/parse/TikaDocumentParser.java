package com.nexus.module.ai.rag.parse;

import com.nexus.common.exception.BusinessException;
import com.nexus.common.result.ResultCode;
import org.apache.tika.detect.DefaultDetector;
import org.apache.tika.detect.Detector;
import org.apache.tika.exception.TikaException;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.mime.MediaType;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.xml.sax.SAXException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;

/**
 * {@link DocumentParser} 的实现：<b>TXT 用 JDK 直读 + 编码探测，PDF 才走 Tika</b>（决策 D13）。
 *
 * <h2>为什么 TXT 不用 Tika（这是本类最重要的一条分工）</h2>
 * {@code new String(bytes, UTF_8)} 就是 TXT 解析的全部，框架在这里不产生价值；
 * 而 TXT 真正的坑是<b>编码</b>（一个 GBK 文件按 UTF-8 解出来是一屏乱码，Tika 也不替我们解决）。
 * 反过来，PDF 这类二进制格式才是 Tika 的用武之地。代价是多一个约 15 行的编码探测，
 * 换来的是把"乱码"从"静默入库一堆垃圾"变成"要么正确解码、要么明确报错"。
 *
 * <h2>三条规则（设计 §4.3，逐条对应三个私有方法）</h2>
 * <ol>
 *     <li><b>扩展名判定在前</b>（{@link #resolveExtension}）：{@code txt} / {@code pdf}，大小写不敏感，
 *         不在白名单 → {@code 10201}；</li>
 *     <li><b>TXT 直读 + 编码探测</b>（{@link #parsePlainText}）：UTF-8 → 替换字符占比超标则 GBK 重解
 *         → 仍超标 → {@code 10203}；</li>
 *     <li><b>PDF 走 Tika</b>（{@link #parsePdf}）：内容检测与扩展名<b>交叉校验</b>（D12），
 *         {@code AutoDetectParser} + {@code BodyContentHandler}（带写入上限，防 PDF 炸弹），
 *         {@code TikaException} / {@code SAXException} 一律转 {@code 10203}。</li>
 * </ol>
 *
 * <h2>白名单是常量，不是配置键</h2>
 * ⚠️ 设计 D12 那句里的 {@code nexus.ai.rag.allowed-extensions} 是<b>笔误</b>（用户 2026-09-22 订正）：
 * 白名单落在本类的 {@link #ALLOWED_EXTENSIONS} 常量上，与 {@code ResultCode} 里 {@code 10201} 的文案
 * "仅支持 TXT / PDF"<b>同源</b>。将来加 Word = 改这个常量 + 加一行 Tika 的 Microsoft 模块依赖
 * （设计 §10.1 写了成本）。刻意不做成配置键：白名单可配而错误文案写死，等于给自己留一个
 * "配置说支持 docx、报错文案说不支持"的失真点。
 *
 * <h2>不打印正文</h2>
 * 与阶段2 的日志纪律一致（设计 §4.11）：日志里出现的是文件名、类型、异常类型与 message、
 * 字符数 —— <b>不是</b>文件内容。用户上传的正文可能很长、也可能含隐私。
 *
 * @author nexus
 */
@Component
public class TikaDocumentParser implements DocumentParser {

    private static final Logger log = LoggerFactory.getLogger(TikaDocumentParser.class);

    /**
     * 支持的文件扩展名（小写，比较前统一归一化）。
     *
     * <p>{@code Set.of} 是<b>不可变</b>的：这份白名单必须是常量，运行期改它没有任何接口，
     * 也就不存在"改了一半"的形态。
     */
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of("txt", "pdf");

    /** 扩展名常量：{@link #parse} 的分支判据。 */
    private static final String EXTENSION_TXT = "txt";

    /** 扩展名常量：{@link #parse} 的分支判据。 */
    private static final String EXTENSION_PDF = "pdf";

    /** 落库与回显用的类型名（大写），与契约 {@code KbDocumentVO.fileType} 的取值一致。 */
    private static final String FILE_TYPE_TXT = "TXT";

    /** 落库与回显用的类型名（大写）。 */
    private static final String FILE_TYPE_PDF = "PDF";

    /** 解析路径标识，只进日志（设计 §4.11 的 {@code parser=...}）。 */
    private static final String PARSER_TIKA = "tika";

    /** 解析路径标识：UTF-8 直读成功。 */
    private static final String PARSER_UTF8 = "utf-8";

    /** 解析路径标识：UTF-8 探测失败后按 GBK 重解成功。 */
    private static final String PARSER_GBK = "gbk";

    /**
     * GBK 字符集。<b>刻意用 {@code Charset.forName("GBK")} 而不是某个常量字段</b>：
     * JDK 没有为 GBK 提供 {@code StandardCharsets} 常量（它不在标准字符集之列），
     * 但它在所有 JDK 实现里都存在（{@code sun.nio.cs.ext}），故构造期不会失败。
     */
    private static final Charset CHARSET_GBK = Charset.forName("GBK");

    /** UTF-8 解码失败的信号字符：{@code U+FFFD} REPLACEMENT CHARACTER。**写转义而不是字面量**。 */
    private static final char REPLACEMENT_CHAR = (char) 0xFFFD;

    /**
     * 替换字符占比的容忍上限（1%）：超过它才认为"这个编码不对"。
     *
     * <p>为什么不是 0：<b>合法文本里也可能出现 U+FFFD</b>（源文档本身就含替换字符）。
     * 用 1% 这个量级区分"个别脏字符"与"整篇解错了" —— 后者（GBK 按 UTF-8 解）的占比接近
     * 三分之一到二分之一，两者差着两个数量级，阈值落在哪里都不敏感。
     */
    private static final double MAX_REPLACEMENT_RATIO = 0.01d;

    /** 连续空行的保留上限（设计 §4.3 的归一化第 3 条：连续 3 个以上空行压成 2 个）。 */
    private static final int MAX_CONSECUTIVE_BLANK_LINES = 2;

    /**
     * Tika 的写入上限（字符数）：<b>防 PDF 炸弹</b>（递归对象 / 高压缩比流可以在几 KB 的输入上
     * 产出 GB 级文本，把解析线程的内存吃光）。
     *
     * <p>取值理由：业务上限是"3000 块 × 500 字 = 1.5M 字符"（{@code nexus.ai.rag.max-chunks-per-document}
     * × {@code chunk-size}），本值 ≈ 它的 3 倍 ⇒ <b>1.5M~5M 之间</b>的文档能被正常解析出来、
     * 再由 service 按 {@code 10204}（"请拆分后上传"）明确拒绝 —— 那比 {@code 10203}（"解析不出文本"）
     * 准确得多；只有超过 5M 才由本闸拦下，那一刻"压缩炸弹 / 异常结构"的可能性已经远大于
     * "用户真的传了一份超长文档"。
     *
     * <p>⚠️ <b>与配置的耦合写在明处</b>：调大 {@code max-chunks-per-document} 时本常量应同步调大
     * （与 {@code ResultCode.FILE_TOO_LARGE} 的文案写死 10MB 是同一类取舍：常量换来的是
     * "这个上限在哪都看得见"，代价是它与配置是两处）。
     */
    private static final int MAX_PARSED_CHARS = 5_000_000;

    /** PDF 的内容检测判据：媒体类型的 subtype 是 {@code pdf}（见 {@link #isPdf} 的注释）。 */
    private static final String MEDIA_SUBTYPE_PDF = "pdf";

    /** 无扩展名时的日志占位（不打印空串，避免日志里出现 {@code extension=} 后什么都没有）。 */
    private static final String NO_EXTENSION = "(无扩展名)";

    /**
     * 启动期一条 info：它是"Tika 真的在 classpath 上"的可见判据。
     *
     * <p>设计 §6.0 把这条日志写成依赖增量是否生效的判定依据 —— 缺 Tika 时本类根本装配不起来
     * （{@code NoClassDefFoundError}），所以"能看到这行"等于"依赖解析成功 + 本类被扫到"。
     */
    public TikaDocumentParser() {
        log.info("TikaDocumentParser 就绪：白名单={} txt解析=JDK直读(UTF-8→GBK探测) pdf解析=tika"
                        + " 写入上限={}字符",
                ALLOWED_EXTENSIONS, MAX_PARSED_CHARS);
    }

    @Override
    public ParsedDocument parse(byte[] content, String fileName) {
        // ① 扩展名判定在前（设计 §4.3 规则 1）：能靠文件名挡掉的，就别去解码内容
        String extension = resolveExtension(fileName);

        // ② 空内容直接归 10203，不走后面的分支。为什么单独一提：0 字节的 .pdf 若走内容检测，
        //    得到的是"无法判定类型"（application/octet-stream 之类）⇒ 会被报成 10201"类型不支持"，
        //    而契约与验收（§7.2 用空文件替代扫描版 PDF）要的是 10203"解析不出文本" ——
        //    扩展名没问题，问题是没有内容。这一格的措辞差异直接决定排查方向
        if (content == null || content.length == 0) {
            log.warn("[kb] 文件内容为空: fileName={} fileType={}", fileName, extension.toUpperCase(Locale.ROOT));
            throw new BusinessException(ResultCode.KB_PARSE_EMPTY);
        }

        if (EXTENSION_TXT.equals(extension)) {
            return parsePlainText(content, fileName);
        }
        // resolveExtension 已保证只剩 pdf（白名单就两项），故这里不需要第三个分支
        return parsePdf(content, fileName);
    }

    /**
     * 取扩展名并做白名单判定（设计 §4.3 规则 1）。
     *
     * @param fileName 原始文件名，可为 {@code null}
     * @return 小写扩展名，只会是 {@code txt} 或 {@code pdf}
     * @throws BusinessException 不在白名单（含无扩展名，10201）
     */
    private static String resolveExtension(String fileName) {
        String name = fileName == null ? "" : fileName;
        int dotIndex = name.lastIndexOf('.');
        // 归一化成小写：契约写明"扩展名大小写不敏感"（REPORT.PDF 与 report.pdf 同等对待）。
        // 用 Locale.ROOT 而不是默认 Locale：土耳其语环境下 "I".toLowerCase() 会得到无点的 ı，
        // 那是本项目唯一会因宿主 Locale 而变的转换，用 ROOT 一次性排除
        String extension = dotIndex < 0 ? "" : name.substring(dotIndex + 1).toLowerCase(Locale.ROOT);

        if (!ALLOWED_EXTENSIONS.contains(extension)) {
            log.warn("[kb] 文件类型不在白名单: fileName={} extension={}", name,
                    extension.isEmpty() ? NO_EXTENSION : extension);
            throw new BusinessException(ResultCode.KB_FILE_TYPE_UNSUPPORTED);
        }
        return extension;
    }

    /**
     * TXT 直读 + 编码探测（设计 §4.3 规则 2）。
     *
     * <p><b>刻意不对 TXT 做 Tika 的内容检测交叉校验</b>（D12 的"两头都看"在 TXT 这一侧由编码探测承担）：
     * Tika 的类型探测带有字符集启发式，一个合法的 GBK 中文 TXT 很可能被判成
     * {@code application/octet-stream} —— 拿它去和 {@code .txt} 扩展名交叉校验，就会把
     * <b>正路文件误杀成 10201</b>。而"内容到底是不是文本"这件事，UTF-8/GBK 双解码都超标已经答了（10203）。
     *
     * @param content 文件字节（非空）
     * @param fileName 文件名，仅用于日志
     * @return 解析结果
     * @throws BusinessException 编码不可识别（10203）
     */
    private static ParsedDocument parsePlainText(byte[] content, String fileName) {
        String text = new String(content, StandardCharsets.UTF_8);
        String parserPath = PARSER_UTF8;

        double utf8Ratio = replacementRatio(text);
        if (utf8Ratio > MAX_REPLACEMENT_RATIO) {
            // UTF-8 解出来一屏替换字符 —— 这是"GBK 文件按 UTF-8 解"的典型形态，再按 GBK 试一次
            String gbkText = new String(content, CHARSET_GBK);
            double gbkRatio = replacementRatio(gbkText);
            if (gbkRatio > MAX_REPLACEMENT_RATIO) {
                // 两种编码都解不像：多半是二进制文件改了后缀，也可能是极端损坏的文本
                log.warn("[kb] TXT 编码不可识别（UTF-8 与 GBK 解码都出现大量替换字符）: "
                                + "fileName={} utf8Ratio={} gbkRatio={} bytes={}",
                        fileName, utf8Ratio, gbkRatio, content.length);
                throw new BusinessException(ResultCode.KB_PARSE_EMPTY);
            }
            log.debug("[kb] TXT 按 GBK 解码成功（UTF-8 解码出现替换字符）: fileName={} utf8Ratio={}",
                    fileName, utf8Ratio);
            text = gbkText;
            parserPath = PARSER_GBK;
        }

        return buildResult(text, FILE_TYPE_TXT, parserPath, fileName);
    }

    /**
     * PDF 走 Tika（设计 §4.3 规则 3）：内容检测交叉校验 → 解析 → 归一化。
     *
     * @param content 文件字节（非空）
     * @param fileName 文件名，仅用于日志
     * @return 解析结果
     * @throws BusinessException 内容不是 PDF（10201）或解析不出文本（10203）
     */
    private static ParsedDocument parsePdf(byte[] content, String fileName) {
        MediaType detected = detectMediaType(content);
        if (!isPdf(detected)) {
            // 改了后缀的文本 / 图片 / 空壳都会落在这里（D12：改个后缀就能骗过"只看扩展名"）
            log.warn("[kb] 扩展名与内容不符: fileName={} extension=pdf detected={}", fileName, detected);
            throw new BusinessException(ResultCode.KB_FILE_TYPE_UNSUPPORTED);
        }
        String text = extractWithTika(content, fileName);
        return buildResult(text, FILE_TYPE_PDF, PARSER_TIKA, fileName);
    }

    /**
     * 用 Tika 的探测器判断真实媒体类型。
     *
     * <p><b>每次调用新建探测器与解析器</b>（而不是做成字段）：Tika 并未承诺
     * {@code Detector} / {@code Parser} 实现的线程安全，而本类是单例 bean、上传请求可以并发 ——
     * 拿"文档里没写"的东西当保证，是这类库最典型的隐性坑。代价是每次上传多几个对象分配
     * （重的是 {@code MimeTypes} 注册表，那是 Tika 内部的静态缓存，不在我们这一次分配里）。
     *
     * <p>⚠️ <b>待实测</b>：{@code new DefaultDetector()} 与 {@code MediaType#getSubtype()} 在
     * Tika 3.2.3 上的可用性未经编译验证（宿主无 mvn）。退路写在明处：换成门面类
     * {@code new org.apache.tika.Tika().detect(InputStream)} 取字符串类型名再比对
     * —— 那是同一个探测器之上的门面，语义等价。
     *
     * @param content 文件字节
     * @return 检测出的媒体类型；无法判定时为 {@code null} 或 {@code application/octet-stream}
     * @throws BusinessException 检测过程自身失败（按 10203 处置，不伪装成"类型不符"）
     */
    private static MediaType detectMediaType(byte[] content) {
        Detector detector = new DefaultDetector();
        try {
            // 刻意不塞 Metadata.RESOURCE_NAME_KEY：一旦带上文件名，探测器就会把"名字说是 pdf"
            // 当作强线索之一 —— 而这里的全部目的恰恰是**不看名字**地判一次真实类型
            return detector.detect(new ByteArrayInputStream(content), new Metadata());
        } catch (IOException ex) {
            // 内存流没有可失败的底层 IO，真抛出来说明 Tika 内部出了问题；
            // 按"读不出内容"处置，不伪装成"类型不符"（两者排查方向不同）
            log.warn("[kb] 内容检测失败，按解析不出文本处置: type={} message={}",
                    ex.getClass().getSimpleName(), ex.getMessage());
            throw new BusinessException(ResultCode.KB_PARSE_EMPTY);
        }
    }

    /**
     * 检测结果是不是 PDF。
     *
     * <p>判据取<b>子类型</b>而不是全名：{@code application/pdf} 是标准取值，但历史上也出现过
     * {@code application/x-pdf} 这类别名 —— 认子类型既不会漏掉别名，也不会把
     * {@code image/jpeg} 这种误判成 PDF。反过来，{@code application/octet-stream}（判不出来）
     * 与 {@code text/plain}（改了后缀的文本）都会落到"不符"那一格，正是要拦的两种。
     *
     * @param detected 检测结果，可为 {@code null}
     * @return {@code true} 表示确实是 PDF
     */
    private static boolean isPdf(MediaType detected) {
        return detected != null && MEDIA_SUBTYPE_PDF.equalsIgnoreCase(detected.getSubtype());
    }

    /**
     * 用 {@code AutoDetectParser} 抽出 PDF 文本。
     *
     * @param content 文件字节，已确认内容是 PDF
     * @param fileName 文件名，仅用于日志
     * @return 抽取出的原始文本（未归一化）
     * @throws BusinessException 加密 / 损坏 / 触达写入上限（统一 10203）
     */
    private static String extractWithTika(byte[] content, String fileName) {
        // 带写入上限的 handler：PDF 炸弹与"递归引用了自己"的畸形文档都在这里被截断，
        // 而不是把内存吃光（见 MAX_PARSED_CHARS 的取值理由）
        BodyContentHandler handler = new BodyContentHandler(MAX_PARSED_CHARS);
        // Metadata 同样刻意不带 RESOURCE_NAME_KEY：解析器的选择应当由内容决定
        Metadata metadata = new Metadata();
        try (InputStream input = new ByteArrayInputStream(content)) {
            new AutoDetectParser().parse(input, handler, metadata, new ParseContext());
        } catch (TikaException | SAXException ex) {
            // 加密 PDF、结构损坏、以及上面那个写入上限被触达时抛出的 SAXException 都归这一档。
            // 只记异常类型与 message：message 是诊断信息（如 "Your document contained more than
            // N characters"），不含正文；正文不进日志（设计 §4.11）
            log.warn("[kb] PDF 解析失败（加密 / 损坏 / 超出写入上限）: fileName={} type={} message={}",
                    fileName, ex.getClass().getSimpleName(), ex.getMessage());
            throw new BusinessException(ResultCode.KB_PARSE_EMPTY);
        } catch (IOException ex) {
            // 同 detectMediaType：内存流不会真的抛 IOException
            log.warn("[kb] 读取 PDF 字节流失败（内存流，属异常情况）: fileName={} type={} message={}",
                    fileName, ex.getClass().getSimpleName(), ex.getMessage());
            throw new BusinessException(ResultCode.KB_PARSE_EMPTY);
        }
        return handler.toString();
    }

    /**
     * 归一化 + 空文本判定，产出最终结果。
     *
     * <p>两条分支（TXT / PDF）都必过这里：PDF 的文本也要按 TXT 的规则归一化（设计 §4.3 规则 3 第 2 条），
     * 而"trim 后为空 → 10203"对两者是同一件事（扫描版 PDF 与空 TXT 的用户动作都是换文件）。
     *
     * @param rawText    解析出的原始文本
     * @param fileType   归一化后的类型（{@code TXT} / {@code PDF}）
     * @param parserPath 解析路径，进日志
     * @param fileName   文件名，进日志
     * @return 解析结果（正文保证非空白）
     * @throws BusinessException 归一化后仍是空白（10203）
     */
    private static ParsedDocument buildResult(String rawText, String fileType, String parserPath, String fileName) {
        String text = normalize(rawText);
        if (text.isBlank()) {
            // 扫描版 PDF 的典型归宿：它有页面、没有文本层（设计 §9 风险 2）。
            // 明确报错而不是入库 0 块 —— 后者会让"上传成功但什么都检索不到"，把排查方向带向检索侧
            log.warn("[kb] 解析结果为空（扫描版 PDF / 空文件 / 纯空白）: fileName={} fileType={} parser={}",
                    fileName, fileType, parserPath);
            throw new BusinessException(ResultCode.KB_PARSE_EMPTY);
        }
        return new ParsedDocument(text, fileType, parserPath);
    }

    /**
     * 文本归一化（设计 §4.3 规则 2 的第 3 条，TXT 与 PDF 共用）。
     *
     * <p>三件事，逐条都有明确目的：
     * <ol>
     *     <li><b>统一换行为 {@code \n}</b>：Tika 抽 PDF 时会给出 {@code \r\n}（有的还混 {@code \r}），
     *         而分块与引用展示都按 {@code \n} 数行 —— 不统一，检索出来的片段在前端会多出空行；</li>
     *     <li><b>行尾空白 strip</b>：PDF 抽出的文本常带成串的尾随空格（版面留白），
     *         它们既占字符数也进 embedding；</li>
     *     <li><b>连续空行压缩</b>：一堆空行会让某个分块整块都是空白。</li>
     * </ol>
     * <b>不做</b>的事同样重要：不去空格、不做列对齐（表格错位属设计 §10.3 的 backlog）、
     * 不 trim 整个文本（只判空）。多做的每一件都是"顺手美化"，而它们都会改变 embedding 的输入。
     *
     * <p><b>包内可见是刻意的</b>（同 {@code OllamaService.parseStream} 的取舍）：单测可以直接喂
     * {@code "a\r\n\r\n\r\n\r\nb"} 这类样本断言输出，不必构造文件、不必起 Spring 上下文。
     *
     * @param rawText 原始文本，不可为 {@code null}（Tika 与 {@code new String} 都保证非 null）
     * @return 归一化后的文本；输入全空白时返回空白串（由调用方判 10203）
     */
    static String normalize(String rawText) {
        String unified = rawText.replace("\r\n", "\n").replace('\r', '\n');
        // -1 保留末尾空串：末尾的换行数也要参与"连续空行"的判定，否则尾部的空行会被悄悄吃掉
        String[] lines = unified.split("\n", -1);

        StringBuilder builder = new StringBuilder(unified.length());
        int blankRun = 0;
        for (String line : lines) {
            String stripped = line.stripTrailing();
            if (stripped.isEmpty()) {
                blankRun++;
                if (blankRun > MAX_CONSECUTIVE_BLANK_LINES) {
                    continue;
                }
            } else {
                blankRun = 0;
            }
            if (builder.length() > 0) {
                builder.append('\n');
            }
            builder.append(stripped);
        }
        return builder.toString();
    }

    /**
     * 计算替换字符（{@code U+FFFD}）在解码结果里的占比。
     *
     * <p>用"占比"而不是"是否出现"：合法文本里也可能有个别 {@code U+FFFD}（见
     * {@link #MAX_REPLACEMENT_RATIO} 的注释），而解错编码时它是成片出现的。
     *
     * @param text 解码结果
     * @return 占比 {@code [0, 1]}；空串记 0
     */
    private static double replacementRatio(String text) {
        if (text.isEmpty()) {
            return 0d;
        }
        int count = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == REPLACEMENT_CHAR) {
                count++;
            }
        }
        return (double) count / text.length();
    }
}
