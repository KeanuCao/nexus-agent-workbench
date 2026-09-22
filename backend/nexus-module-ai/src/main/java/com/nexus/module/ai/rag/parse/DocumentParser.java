package com.nexus.module.ai.rag.parse;

import com.nexus.common.exception.BusinessException;
import com.nexus.common.result.ResultCode;

/**
 * 文档解析端口：<b>怎么把一份文件的字节变成纯文本</b>。
 *
 * <p>与 {@code EmbeddingService} 一起构成本阶段的两个"端口"（设计 D4/D13）：调用方
 * （{@code KbDocumentServiceImpl}）只知道"给字节、拿文本"，TXT 的编码探测、PDF 的 Tika、
 * 将来 Word 的解析器选择都关在实现里 —— 所以 {@code rag} 包内<b>除了本接口没有第二个类</b>
 * 认识 Tika（判据：{@code grep -rn "org.apache.tika" ...} 只应命中 {@code TikaDocumentParser}）。
 *
 * <h2>实现要遵守的失败约定</h2>
 * <ul>
 *     <li><b>扩展名不在白名单</b> / <b>扩展名与内容检测不符</b>：抛
 *         {@link BusinessException}（{@link ResultCode#KB_FILE_TYPE_UNSUPPORTED}，10201）；</li>
 *     <li><b>解析不出文本</b>（扫描版 PDF、空文件、编码不可识别、加密/损坏文件）：抛
 *         {@link BusinessException}（{@link ResultCode#KB_PARSE_EMPTY}，10203）。上游解析器的异常
 *         （{@code TikaException} / {@code SAXException}）<b>不得原样上抛</b> —— 它们带内部类名与
 *         栈信息，而用户的动作只有"换一份文件"。</li>
 * </ul>
 * 两种都是 HTTP 200 出口的业务失败（契约 §7.7）：<b>能明确告诉用户"换个文件"的，就不要报成 500</b>。
 *
 * <p>与本项目其他端口一致，此处<b>不收 {@code IOException} 的检查异常</b>：实现内部读的是内存里的
 * 字节数组（调用方已把上传内容读成 {@code byte[]}），真正的 IO 失败发生在调用方读 multipart 时。
 *
 * @author nexus
 */
public interface DocumentParser {

    /**
     * 解析一份文件的内容。
     *
     * @param content  文件字节（调用方已按 {@code spring.servlet.multipart.max-file-size} 限过大小）
     * @param fileName 原始文件名，用于取扩展名与日志；<b>不用于任何落盘</b>（本轮不保存原始文件，
     *                 决策 D8）
     * @return 纯文本 + 解析元数据
     * @throws BusinessException 类型不支持（10201）或解析不出文本（10203）
     */
    ParsedDocument parse(byte[] content, String fileName);
}
