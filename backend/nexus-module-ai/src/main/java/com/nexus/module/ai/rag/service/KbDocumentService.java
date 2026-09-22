package com.nexus.module.ai.rag.service;

import com.nexus.module.ai.rag.dto.KbDocumentListVO;
import com.nexus.module.ai.rag.dto.KbDocumentVO;

/**
 * 知识库文档管理端口：<b>上传 / 列表 / 删除</b>（契约：{@code docs/api/README.md} §7.1~§7.3）。
 *
 * <h2>为什么入参是 {@code byte[]} 而不是 {@code MultipartFile}</h2>
 * 控制器在拿到字节之前只做"文件为空"这一层预检，随后把<b>内容与文件名</b>交给本端口。
 * 好处是业务层不认识任何 multipart 类型（那是 MVC 层的事），
 * 单测也不必构造 {@code MockMultipartFile} —— 一个 {@code new byte[]{...}} 就是全部入参。
 *
 * <h2>同步口径（决策 D7）</h2>
 * {@link #upload} 返回时，解析 → 分块 → 向量化 → 入库<b>已经全部完成</b>：
 * 既没有"已受理"这类中间态，也没有状态列与轮询接口。代价有两个，都记在明处：
 * ① 请求线程被占住直到结束（10MB PDF 约 10~60s）；② 事务里含网络调用（embedding）。
 * 演进方向（异步入库 + 状态列）见设计 §10.5。
 *
 * <h2>租户上下文</h2>
 * 本链路全程在<b>请求线程</b>上（无工作线程、无 {@code SseEmitter}）⇒ {@code TenantContext}
 * 直接可用，不需要像对话接口那样把登录态搬进 {@code CallerContext}（阶段2 决策 D10 的那个坑）。
 *
 * @author nexus
 */
public interface KbDocumentService {

    /**
     * 上传并入库一份文档（{@code POST /api/kb/documents}）。
     *
     * @param content  文件字节（调用方已按 {@code spring.servlet.multipart.max-file-size} 限过大小）
     * @param fileName 原始文件名（取扩展名与展示用；<b>不落盘</b>）
     * @return 入库后的文档（含 {@code documentId} 与 {@code chunkCount}）
     * @throws com.nexus.common.exception.BusinessException 类型不支持（10201）、解析不出文本（10203）、
     *                                                     分块数超限（10204）；向量化时上游不可用（20100，事务回滚）
     */
    KbDocumentVO upload(byte[] content, String fileName);

    /**
     * 列出<b>当前租户</b>的全部文档（{@code GET /api/kb/documents}）。
     *
     * @return 文档列表（无数据时 {@code items} 为空列表，{@code total = 0}）
     */
    KbDocumentListVO list();

    /**
     * 删除一份文档及其全部分块（{@code DELETE /api/kb/documents/{documentId}}）。
     *
     * <p>分块由 {@code t_kb_chunk.document_id} 上的 {@code ON DELETE CASCADE} 一并删除 ——
     * 业务代码<b>只删一次</b>（少一处"忘了删分块"的可能，契约 §7.3 第 2 条）。
     *
     * @param documentId 文档 ID
     * @throws com.nexus.common.exception.BusinessException 文档不存在<b>或不属于当前租户</b>（10202 ——
     *                                                     两种情况刻意同形，区分开等于给出 id 探测口）
     */
    void delete(long documentId);
}
