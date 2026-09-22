package com.nexus.module.ai.rag.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.nexus.common.exception.BusinessException;
import com.nexus.common.exception.SystemException;
import com.nexus.common.result.ResultCode;
import com.nexus.infrastructure.tenant.TenantContext;
import com.nexus.module.ai.rag.chunk.TextChunker;
import com.nexus.module.ai.rag.config.RagProperties;
import com.nexus.module.ai.rag.dto.KbDocumentListVO;
import com.nexus.module.ai.rag.dto.KbDocumentVO;
import com.nexus.module.ai.rag.embedding.EmbeddingService;
import com.nexus.module.ai.rag.entity.KbDocument;
import com.nexus.module.ai.rag.mapper.KbChunkMapper;
import com.nexus.module.ai.rag.mapper.KbDocumentMapper;
import com.nexus.module.ai.rag.mapper.VectorTypeHandler;
import com.nexus.module.ai.rag.parse.DocumentParser;
import com.nexus.module.ai.rag.parse.ParsedDocument;
import com.nexus.module.ai.rag.service.KbDocumentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * {@link KbDocumentService} 的实现：上传链路编排（设计 §4.8）+ 列表 + 删除。
 *
 * <h2>编排顺序（每一步都有理由，改动前先读 §4.8 的六条纪律）</h2>
 * <pre>
 * 上传开始（日志）
 *   → 预检（文件名长度）    ← 失败 40001；与控制器那条"文件为空"同一层，失败得越早越好
 *   → 解析（事务外）        ← 失败 10201 / 10203，此时还没开事务，也不该有事务开销
 *   → 分块（事务外）
 *   → 上限检查（★ 向量化之前）← 失败 10204，几秒内发生；不判则 ≈ 75 分钟（算式见 10204 的注释）
 *   → 事务 { 写文档 → 每批(向量化 + 写分块) }   ← 要么全成、要么全不成
 *   → 入库完成（日志）
 * </pre>
 *
 * <h2>事务边界为什么用 {@link TransactionTemplate} 而不是 {@code @Transactional}</h2>
 * 需要事务的只是 {@link #upload} 的<b>最后一段</b>（写库 + 向量化），而 Spring 的注解事务靠
 * <b>代理</b>生效 —— 同类内部调用不走代理，把 {@code @Transactional} 标在一个内部方法上是
 * <b>静默失效</b>的经典写法（事务根本没开，失败时留下一份"有文档、没分块"的半成品）。
 * 两条正路：把这一段拆成一个新 Bean，或用编程式事务。这里选后者：事务边界与它保护的那几行
 * 写在<b>同一个位置</b>，不必为一个 20 行的段落新增一个类（也不必自我注入）。
 *
 * <p>回滚条件：{@code TransactionTemplate} 默认对 {@code RuntimeException} 回滚 —— 本链路的所有
 * 失败都是 {@code BusinessException} / {@code SystemException}（两个端口都不抛检查异常），
 * 与设计要的 {@code rollbackFor = Exception.class} 在本项目里等价。
 *
 * <h2>{@code tenant_id} 一行都没写（决策 D11）</h2>
 * 文档插入由 {@code KbDocumentMapper.insert} 生成语句（不含 tenant_id 列，拦截器补），
 * 分块插入由 {@link KbChunkMapper#insertChunk} 生成语句（同样不含）。判据：mapper 的 debug 日志。
 *
 * @author nexus
 */
@Service
public class KbDocumentServiceImpl implements KbDocumentService {

    private static final Logger log = LoggerFactory.getLogger(KbDocumentServiceImpl.class);

    /**
     * 每批的向量化 / 插入条数（设计 §4.8 的 "loop 每批 16 块"）。
     *
     * <p>它同时界定了两件事的大小：<b>一次 embedding 请求</b>与<b>一条 INSERT 语句</b>。
     * 与 {@code OllamaEmbeddingService} 内部的 {@code EMBED_BATCH_SIZE} 取同一个值是巧合、不是耦合 ——
     * 那两个数字各自管一头（HTTP 请求体 / SQL 语句体），将来谁改都不影响另一个。
     */
    private static final int INGEST_BATCH_SIZE = 16;

    /**
     * 文件名的字符上限 = {@code t_kb_document.file_name VARCHAR(255)} 的列宽
     * （{@code db-patch/202609221000_初始化知识库表.sql}）。
     *
     * <p><b>为什么要在业务侧判</b>：不判的形态是 PostgreSQL 拒绝插入
     * （{@code value too long for type character varying(255)}）→ 500 + {@code 50000}「系统繁忙」——
     * 把<b>客户端的输入问题</b>报成服务端故障，正是本仓库最防的那类误导
     * （同 {@code GlobalExceptionHandler} 里那四个"请求形状"出口的由来）。
     *
     * <p>⚠️ <b>代价：这个 255 与 DDL 是两处</b>，改列宽必须同时改这里
     * （与 {@code ResultCode.FILE_TOO_LARGE} 的文案写死 "10MB" 属同一类取舍，契约已记在明处）。
     * 不引 {@code information_schema} 查询来消除重复：为一条预检加一次启动期元数据查询，
     * 换来的是"列宽变了但缓存没刷新"这种新的失效形态，不划算。
     *
     * <p><b>口径：{@code String.length()}（UTF-16 单元）</b>，比 PostgreSQL 的
     * {@code varchar(n)}（按<b>码点</b>计）更严格 —— 含 emoji 的名字可能被本检查提前拒掉
     * （200 个 emoji 在 Java 里是 400）。<b>偏差方向是安全的</b>：宁可早拒，绝不放行到数据库。
     */
    private static final int MAX_FILE_NAME_LENGTH = 255;

    private final DocumentParser documentParser;

    private final TextChunker textChunker;

    private final EmbeddingService embeddingService;

    private final KbDocumentMapper documentMapper;

    private final KbChunkMapper chunkMapper;

    private final RagProperties ragProperties;

    /** 编程式事务（见类注释：为什么不用 {@code @Transactional}）。 */
    private final TransactionTemplate transactionTemplate;

    /**
     * @param documentParser     解析端口（TXT 直读 / PDF 走 Tika）
     * @param textChunker        分块（纯函数组件，参数来自配置）
     * @param embeddingService   向量化端口
     * @param documentMapper     文档表 Mapper（通用 CRUD）
     * @param chunkMapper        分块表 Mapper（自定义 SQL）
     * @param ragProperties      RAG 配置：本类用 {@code chunk-*} / {@code max-chunks-per-document}
     * @param transactionManager 由 {@code spring-boot-starter-jdbc} 的自动装配提供（数据源事务管理器）
     */
    public KbDocumentServiceImpl(DocumentParser documentParser,
                                 TextChunker textChunker,
                                 EmbeddingService embeddingService,
                                 KbDocumentMapper documentMapper,
                                 KbChunkMapper chunkMapper,
                                 RagProperties ragProperties,
                                 PlatformTransactionManager transactionManager) {
        this.documentParser = documentParser;
        this.textChunker = textChunker;
        this.embeddingService = embeddingService;
        this.documentMapper = documentMapper;
        this.chunkMapper = chunkMapper;
        this.ragProperties = ragProperties;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Override
    public KbDocumentVO upload(byte[] content, String fileName) {
        long startNanos = System.nanoTime();
        Long userId = TenantContext.getUserId();
        Long tenantId = TenantContext.getTenantId();
        int fileSize = content == null ? 0 : content.length;
        // 上传开始：三个定位信息（谁、哪个租户、多大）。**不打文件内容**（设计 §4.11 的日志纪律）
        log.info("[kb] 上传开始: userId={} tenantId={} fileName={} size={}", userId, tenantId, fileName, fileSize);

        // ① 预检（与"文件为空"同在控制器/服务的最前面）：能在这里挡掉的，就别让它走到解析与写库
        validateFileNameLength(fileName);

        // ② 解析（事务之外）：扩展名白名单 / 内容交叉校验 / 编码探测都在这一个调用里（10201 / 10203）
        long parseStartNanos = System.nanoTime();
        ParsedDocument parsed = documentParser.parse(content, fileName);
        log.info("[kb] 解析完成: fileName={} fileType={} parser={} chars={} durationMs={}",
                fileName, parsed.fileType(), parsed.parserPath(), parsed.charCount(),
                elapsedMsSince(parseStartNanos));

        // ③ 分块（事务之外）
        List<String> chunks = textChunker.chunk(parsed.text());
        log.info("[kb] 分块完成: fileName={} chunks={} chunkSize={} overlap={}",
                fileName, chunks.size(), ragProperties.getChunkSize(), ragProperties.getChunkOverlap());

        // ④ 两道业务闸，都判在**向量化之前**（决策 D15）
        if (chunks.isEmpty()) {
            // 解析层已挡掉空文本（10203），走到这里说明正文全是空白或分块参数异常。
            // 报 10203 而不是 500：用户的动作与"换一份文件"完全相同（能明确告诉用户换文件的，
            // 就不要报成系统故障）
            log.warn("[kb] 分块结果为空: fileName={} chars={}", fileName, parsed.charCount());
            throw new BusinessException(ResultCode.KB_PARSE_EMPTY);
        }
        int maxChunks = ragProperties.getMaxChunksPerDocument();
        if (chunks.size() > maxChunks) {
            // 10MB 的 TXT ≈ 2 万块 ÷ 每批 16 块 = 1250 批 ≈ 75 分钟（实测 3.6 s/批）—— 在这里停住：
            // 分块刚结束就失败，一行向量都还没算；否则用户看到的是一次"像上游超时"的失败，
            // 排查方向整个是错的
            log.warn("[kb] 分块数超限: fileName={} chunks={} max={}", fileName, chunks.size(), maxChunks);
            throw new BusinessException(ResultCode.KB_CONTENT_TOO_LARGE);
        }

        // ⑤ 写库 + 向量化：一个事务，要么全成、要么全不成
        KbDocumentVO document = transactionTemplate.execute(status ->
                ingest(parsed, chunks, fileName, fileSize, userId));

        log.info("[kb] 入库完成: documentId={} tenantId={} chunkCount={} durationMs={}",
                document.documentId(), tenantId, document.chunkCount(), elapsedMsSince(startNanos));
        return document;
    }

    @Override
    public KbDocumentListVO list() {
        // ⚠️ 契约不保证顺序（§7.2），这里固定按 document_id 倒序，只为让刷新前后稳定：
        //    自增主键的顺序即入库顺序，"新的在前"是列表最不需要解释的排法
        List<KbDocument> documents = documentMapper.selectList(
                new LambdaQueryWrapper<KbDocument>().orderByDesc(KbDocument::getDocumentId));
        // 跨租户的表现是"看不见"—— 条件由拦截器注入，业务代码里没有 tenant_id（决策 D11）
        List<KbDocumentVO> items = documents.stream().map(KbDocumentVO::from).toList();
        log.debug("[kb] 文档列表: tenantId={} total={}", TenantContext.getTenantId(), items.size());
        return KbDocumentListVO.of(items);
    }

    @Override
    public void delete(long documentId) {
        // 不需要事务：单条 DELETE 本身即原子，分块的级联删除是数据库表定义里的事（ON DELETE CASCADE）
        int affected = documentMapper.deleteById(documentId);
        if (affected == 0) {
            // "不存在"与"不属于当前租户"同形（契约 §7.3 第 1 条）：多给一个信号就等于给出 id 探测口
            log.warn("[kb] 删除未命中: documentId={} tenantId={}", documentId, TenantContext.getTenantId());
            throw new BusinessException(ResultCode.KB_DOCUMENT_NOT_FOUND);
        }
        log.info("[kb] 删除完成: documentId={} tenantId={}（分块由外键级联删除）",
                documentId, TenantContext.getTenantId());
    }

    /**
     * 事务内的那一段：写文档行 → 分批（向量化 + 写分块）。
     *
     * <p>本方法<b>只在</b> {@link TransactionTemplate} 的回调里调用（不要直接调：那样没有事务，
     * 失败会留下"有文档、没分块"的半成品，而 {@code chunk_count} 还写着预期的块数）。
     *
     * @param parsed   解析结果
     * @param chunks   分块（非空、已过上限检查）
     * @param fileName 原始文件名
     * @param fileSize 上传字节数
     * @param userId   上传者（取自 {@code TenantContext}，不是请求体 —— 那是客户端可伪造的输入）
     * @return 入库后的文档视图
     * @throws com.nexus.common.exception.BusinessException 向量化时上游不可用（20100）⇒ 整个事务回滚
     * @throws SystemException 插入行数与预期不符（见方法内注释）
     */
    private KbDocumentVO ingest(ParsedDocument parsed, List<String> chunks, String fileName,
                                int fileSize, Long userId) {
        OffsetDateTime now = OffsetDateTime.now();
        KbDocument document = new KbDocument();
        document.setFileName(fileName);
        document.setFileType(parsed.fileType());
        document.setFileSize((long) fileSize);
        document.setCharCount(parsed.charCount());
        document.setChunkCount(chunks.size());
        document.setCreatedBy(userId);
        document.setCreatedAt(now);
        // updated_at 与 created_at 取同一时刻：本轮没有更新路径，一行的两个时间戳不该差几毫秒
        document.setUpdatedAt(now);
        // 语句里没有 tenant_id 列（拦截器补），插入后 document_id 由 MP 回填到实体
        documentMapper.insert(document);
        long documentId = document.getDocumentId();

        long embedNanos = 0L;
        int batches = 0;
        for (int from = 0; from < chunks.size(); from += INGEST_BATCH_SIZE) {
            int to = Math.min(from + INGEST_BATCH_SIZE, chunks.size());
            // subList 是视图（不拷贝）；本方法内只读，安全
            List<String> batch = chunks.subList(from, to);

            long batchStartNanos = System.nanoTime();
            List<float[]> vectors = embeddingService.embedDocuments(batch);
            embedNanos += System.nanoTime() - batchStartNanos;

            List<KbChunkMapper.ChunkRow> rows = new ArrayList<>(batch.size());
            for (int i = 0; i < batch.size(); i++) {
                String text = batch.get(i);
                // chunk_index 用**全局**下标（from + i）而不是批内下标 —— 分批是实现细节，
                // 不能让它泄漏到"第几段"这个用户可见的编号里
                rows.add(new KbChunkMapper.ChunkRow(
                        from + i,
                        text,
                        text.length(),
                        VectorTypeHandler.toVectorLiteral(vectors.get(i))));
            }

            int affected = chunkMapper.insertChunk(documentId, rows);
            if (affected != rows.size()) {
                // 插进去的行数与预期不符 ⇒ chunk_count（列表页给人看的排查数据）会说谎，
                // 而"分块少了"在检索侧只表现为"某段查不到"，极难倒查。宁可当场失败
                throw new SystemException("分块插入行数与预期不符：期望 " + rows.size() + " 行，实际 "
                        + affected + " 行（documentId=" + documentId + "）");
            }
            batches++;
        }

        log.info("[kb] 向量化完成: fileName={} chunks={} batches={} durationMs={}",
                fileName, chunks.size(), batches, embedNanos / 1_000_000L);
        return KbDocumentVO.from(document);
    }

    /**
     * 文件名长度预检：超过 {@link #MAX_FILE_NAME_LENGTH} 直接 {@code 40001}，不让它走到数据库。
     *
     * <p>不判的话，PostgreSQL 的列宽约束会以 {@code DataIntegrityViolationException} 现形，
     * 最终给用户一句 500「系统繁忙」—— 而用户真正要做的是"把文件名改短"。
     *
     * <p>{@code null} 文件名<b>放行</b>：那不是本检查的职责（扩展名判不出来时，
     * {@code TikaDocumentParser} 会以 {@code 10201} 明确拒绝），不在此重复报错。
     *
     * @param fileName 原始文件名（可为 {@code null}）
     * @throws BusinessException 文件名超长（40001，码取自枚举、文案带上下文）
     */
    private static void validateFileNameLength(String fileName) {
        if (fileName == null || fileName.length() <= MAX_FILE_NAME_LENGTH) {
            return;
        }
        // 只记长度，**不打文件名全文**（它与分块正文同属"用户上传的内容"，设计 §4.11 的日志纪律）
        log.warn("[kb] 文件名超长: length={} max={}", fileName.length(), MAX_FILE_NAME_LENGTH);
        throw new BusinessException(ResultCode.PARAM_INVALID.getCode(),
                "文件名过长，最多 " + MAX_FILE_NAME_LENGTH + " 个字符");
    }

    /**
     * 从纳秒起点换算耗时（毫秒）。
     *
     * @param startNanos {@code System.nanoTime()} 的起点
     * @return 毫秒数
     */
    private static long elapsedMsSince(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }
}
