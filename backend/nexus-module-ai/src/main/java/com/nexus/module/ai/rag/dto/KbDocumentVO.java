package com.nexus.module.ai.rag.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.nexus.module.ai.rag.entity.KbDocument;

import java.time.OffsetDateTime;

/**
 * 知识库文档（契约：{@code docs/api/README.md} §7.5）：<b>上传响应与列表项同构</b>。
 *
 * <p>同构是刻意的：上传成功后前端拿到的结构与随后刷新列表拿到的是同一个，页面只需要一条渲染路径
 * （反过来说，若上传返回"受理凭证"、列表返回"文档详情"，前端就得写两个模型 —— 见设计 D7 的同步口径）。
 *
 * <p>字段顺序即契约顺序（Jackson 对 record 按组件声明顺序序列化，无需 {@code @JsonPropertyOrder}）。
 *
 * <p><b>数值字段一律用基本类型</b>：契约把它们都列为必填，而 {@code null} 序列化出来是
 * {@code "documentId": null} —— 那是对契约的静默违约，前端只会看到一个莫名其妙的 undefined。
 * 用基本类型时，"实体里本不该为空的值真的是空的"会在映射那一刻以 NPE 现形（500 + 服务端堆栈），
 * 属正确的 fail-fast。
 *
 * @param documentId 文档 ID（删除接口的路径参数）；由 MP 的 {@code IdType.AUTO} 在插入后回填
 * @param fileName   原始文件名（<b>仅回显，不落盘</b>，决策 D8）
 * @param fileType   {@code TXT} / {@code PDF}（大写，由扩展名归一化而来）
 * @param fileSize   上传字节数
 * @param charCount  解析出的正文字符数（排查解析质量的第一眼数据）
 * @param chunkCount 入库的分块数（= 3000 上限判据的实测值）
 * @param createdAt  入库时间，格式与 {@code /api/health} 的 {@code timestamp} <b>同款</b>：
 *                   {@code yyyy-MM-dd'T'HH:mm:ssXXX}（秒级、无小数秒）。
 *                   <b>偏移量随服务端时区</b>（容器内通常为 {@code +00:00}）—— 刻意不做
 *                   {@code +08:00} 硬转：契约只要求"带偏移"，硬转等于在应用侧造一个
 *                   "谁是本地时区"的假设，而这个假设在容器里通常是错的
 * @author nexus
 */
public record KbDocumentVO(
        long documentId,
        String fileName,
        String fileType,
        long fileSize,
        int charCount,
        int chunkCount,
        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ssXXX")
        OffsetDateTime createdAt) {

    /**
     * 由实体转换（唯一的构造入口 —— 三个出口的字段口径因此不可能分家）。
     *
     * @param document 已入库的文档实体（{@code documentId} 与 {@code createdAt} 必须已有值：
     *                 前者由 MP 回填、后者由服务层赋值，见 {@link KbDocument} 的类注释）
     * @return 契约形状的 VO
     */
    public static KbDocumentVO from(KbDocument document) {
        return new KbDocumentVO(
                document.getDocumentId(),
                document.getFileName(),
                document.getFileType(),
                document.getFileSize(),
                document.getCharCount(),
                document.getChunkCount(),
                document.getCreatedAt());
    }
}
