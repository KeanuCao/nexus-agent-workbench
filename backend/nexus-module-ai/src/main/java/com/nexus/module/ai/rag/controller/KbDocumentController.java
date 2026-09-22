package com.nexus.module.ai.rag.controller;

import com.nexus.common.exception.BusinessException;
import com.nexus.common.exception.SystemException;
import com.nexus.common.result.Result;
import com.nexus.common.result.ResultCode;
import com.nexus.module.ai.rag.dto.KbDocumentListVO;
import com.nexus.module.ai.rag.dto.KbDocumentVO;
import com.nexus.module.ai.rag.service.KbDocumentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

/**
 * 知识库文档接口（契约：{@code docs/api/README.md} §7.1~§7.3 / {@code docs/api/openapi.yaml}）。
 *
 * <p>路径约定：不在类上挂 {@code @RequestMapping}，完整路径与 {@code produces}/{@code consumes}
 * 都写在方法注解上（CLAUDE.md 宪法要求显式声明接口契约，与 {@code HealthController} /
 * {@code ChatController} 同一风格）。★ 这里声明 {@code consumes = multipart/form-data} 只影响
 * "服务端接受什么"；前端侧<b>必须</b>让请求头是<b>带 boundary 的</b> {@code multipart/form-data}
 * —— 显式声明 {@code 'Content-Type': 'multipart/form-data'} 即可（axios 的浏览器适配器会把这个头删掉、
 * 由浏览器补 boundary；实测口径见契约 §7.1 约定 2）。⚠️ 请求头不对时本接口在<b>映射阶段</b>就被
 * {@code consumes} 条件挡下（{@code HttpMediaTypeNotSupportedException}，到不了
 * {@code MultipartException}），由 {@code GlobalExceptionHandler} 按"该接口是否消费 multipart"
 * 分流成 <b>200 + 40001</b>（契约 §7.7 第 2 行）。
 *
 * <h2>三层失败出口（谁在哪里处置）</h2>
 * <table border="1">
 *     <caption>上传接口的失败与载体</caption>
 *     <tr><th>失败</th><th>载体</th><th>HTTP</th><th>code</th></tr>
 *     <tr><td>没带 token / token 失效</td><td>过滤器直接写 {@code Result}</td><td>401</td><td>40100/40101/40102</td></tr>
 *     <tr><td>请求不是 multipart、缺 {@code file} 字段、文件超 10MB</td>
 *         <td>全局异常处理器的出口（含 415 出口里按 multipart 分流的那一格，2026-09-22 补）</td>
 *         <td>200</td><td>40001 / 40003</td></tr>
 *     <tr><td>文件为空（0 字节）</td><td>本类预检</td><td>200</td><td>40001</td></tr>
 *     <tr><td>类型不支持 / 解析不出文本 / 分块超限</td><td>服务层</td><td>200</td><td>10201 / 10203 / 10204</td></tr>
 *     <tr><td>向量化时上游不可用</td><td>服务层（事务回滚）</td><td><b>503</b></td><td>20100</td></tr>
 * </table>
 *
 * @author nexus
 */
@RestController
public class KbDocumentController {

    private static final Logger log = LoggerFactory.getLogger(KbDocumentController.class);

    private final KbDocumentService kbDocumentService;

    public KbDocumentController(KbDocumentService kbDocumentService) {
        this.kbDocumentService = kbDocumentService;
    }

    /**
     * {@code POST /api/kb/documents}：上传一份 TXT / PDF 并<b>同步完成入库</b>（决策 D7）。
     *
     * <p>返回 200 时解析、分块、向量化、入库已经全部完成 —— 不是"已受理"。
     * 因此本接口的耗时是<b>数十秒~两分钟量级</b>（取决于块数与 CPU：验收夹具 1.6MB PDF / 587 块
     * 实测 <b>131.9 s</b>，2026-09-22 端到端两次一致；耗时只当参考记录值，口径见设计 §5.1-4），
     * 前端必须逐请求覆盖超时，且超时文案要写"服务端可能仍在处理"（契约 §7.1 第 3 条）。
     *
     * <p><b>这里只做"文件为空"一层预检</b>，不重复判扩展名：白名单的唯一真源在
     * {@code TikaDocumentParser}（常量 {@code Set.of("txt", "pdf")} + 内容交叉校验，决策 D12）。
     * 在控制器再抄一份白名单，就会出现"两处白名单"—— 而两处早晚会不一致，
     * 且不一致的那一天没有任何征兆（多格式范围决策见设计 §10.1）。
     *
     * @param file 上传文件（字段名必须是 {@code file}：写成别的名字框架会抛
     *             {@code MissingServletRequestPartException} → 40001）
     * @return {@code Result<KbDocumentVO>}（上传响应与列表项同构）
     */
    @PostMapping(value = "/api/kb/documents",
            produces = MediaType.APPLICATION_JSON_VALUE,
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Result<KbDocumentVO> upload(@RequestParam("file") MultipartFile file) {
        // 参数不可为 null：缺 part 时框架在进入本方法之前就抛 MissingServletRequestPartException
        //（那个出口同样是 40001）—— 故这里只判"字段在、但内容为空"这一格（0 字节文件）
        if (file.isEmpty()) {
            log.warn("[kb] 上传文件为空: fileField={} originalFilename={}",
                    file.getName(), file.getOriginalFilename());
            throw new BusinessException(ResultCode.PARAM_INVALID);
        }

        byte[] content;
        try {
            content = file.getBytes();
        } catch (IOException ex) {
            // multipart 临时文件读不出来（磁盘/临时目录故障）：属**服务端**问题，
            // 不要包装成 40001 —— 那会让用户去改一个本来就对的请求
            throw new SystemException("读取上传文件内容失败（multipart 临时文件不可读）", ex);
        }
        // 字节与文件名一起交给服务层：业务层从此不认识任何 multipart 类型（同 KbDocumentService 的注释）
        return Result.success(kbDocumentService.upload(content, file.getOriginalFilename()));
    }

    /**
     * {@code GET /api/kb/documents}：列出当前租户的文档（跨租户"看不见"，不是报错）。
     *
     * @return {@code Result<KbDocumentListVO>}
     */
    @GetMapping(value = "/api/kb/documents", produces = MediaType.APPLICATION_JSON_VALUE)
    public Result<KbDocumentListVO> list() {
        return Result.success(kbDocumentService.list());
    }

    /**
     * {@code DELETE /api/kb/documents/{documentId}}：删除文档（其分块经外键级联一并删除）。
     *
     * <p>成功返回 {@code data = null}；文档不存在<b>或不属于当前租户</b> → 200 + {@code 10202}
     * （两种情形刻意同形，契约 §7.3 第 1 条）。
     *
     * @param documentId 文档 ID（int64 路径参数）
     * @return {@code Result<Void>}
     */
    @DeleteMapping(value = "/api/kb/documents/{documentId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public Result<Void> delete(@PathVariable("documentId") long documentId) {
        kbDocumentService.delete(documentId);
        return Result.success();
    }
}
