package com.nexus.module.ai.rag.embedding;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.common.exception.BusinessException;
import com.nexus.common.exception.SystemException;
import com.nexus.common.result.ResultCode;
import com.nexus.module.ai.gateway.config.AiProperties;
import com.nexus.module.ai.rag.config.RagProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * {@link EmbeddingService} 的实现：调本地 Ollama 的 {@code POST {base-url}{embed-path}}（默认
 * {@code /api/embed}），把一批文本换成一批向量。
 *
 * <h2>上游协议（✅ 2026-09-22 探针 A/B 实测：端点存在、批量入参被接受）</h2>
 * <pre>
 * 请求：{"model":"bge-m3","input":["第一块","..."]}
 * 响应：{"model":"bge-m3","embeddings":[[0.1,0.2,...], ...]}   ← 1024 维；支持批量
 * </pre>
 * ⚠️ 上面那个 <b>1024 是 bge-m3 的公布值，不是本机实测值</b>（依据与复核方式见
 * {@link #EXPECTED_DIMENSION} 那条注释）：换模型时 {@code nexus-ollama} 里还没拉 bge-m3，
 * 2026-09-22 的探针 A 量到的是 nomic 的 768。
 *
 * <p>⚠️ <b>退路备查</b>（只改本类，端口签名不变 —— 这正是"embedding 自成端口"的价值）：
 * 若将来换了 Ollama 版本、{@code /api/embed} 不存在，退到旧的 {@code /api/embeddings}
 * （单条入参 {@code {"model":..,"prompt":".."}} → 单条响应 {@code {"embedding":[..]}}），
 * 那时把批量循环改成逐条调用、把 {@link UpstreamEmbedRequest} 的 {@code input} 换成 {@code prompt} 即可。
 *
 * <h2>前缀在这里加（决策 D14 的机制保留，默认值已随换模型清空）</h2>
 * 这两个前缀本是 {@code nomic-embed-text} 模型卡的建议（v1.5 口径）：入库加 {@code search_document: }、
 * 查询加 {@code search_query: }。换成 {@code bge-m3} 后<b>两个默认值都改成空串</b> —— bge-m3
 * <b>不需要</b>任务前缀，留着等于给一个不期望它们的模型<b>硬塞英文任务前缀</b>：不报错、只是检索变差，
 * 属本仓库最防的"静默劣化"一类，故与换模型**同一次**改掉（TC-03 的 A/B 用例正是靠这两个键一开一关跑的）。
 * 机制本身照旧保留：两个前缀由<b>实现内部</b>持有（而不是让调用方拼，理由见 {@link EmbeddingService}
 * 的类注释），来自 {@code nexus.ai.rag.document-prefix} / {@code query-prefix}，<b>置空即关闭</b>。
 *
 * <h2>三个写死的常量（都对应一个"缺的配置键"，已在交付说明里上报）</h2>
 * <ul>
 *     <li>{@link #EMBED_MODEL}：上游模型名。yml 里只有 {@code nexus.ai.ollama.model}
 *         （那是<b>对话</b>模型 {@code qwen2.5:7b}，拿它来向量化是错的），
 *         没有 {@code embed-model} 这个键 —— 故先落常量。它的"单一真源"实际在
 *         {@code docker-compose/docker-compose.yml} 的 {@code ollama-init} 拉取清单里。
 *         <b>2026-09-22 时点该清单仍写着 {@code qwen2.5:7b nomic-embed-text}，换模型必须同步改它</b>
 *         —— 本类不改 compose（不在本次交付范围内），改它之前<b>每次向量化都会拿到
 *         {@code model 'bge-m3' not found} → 20100</b>。清单也没有 bge-m3 时，devops 可先手工
 *         {@code docker exec nexus-ollama ollama pull bge-m3} 顶一下。将来补配置键时建议叫
 *         {@code nexus.ai.ollama.embed-model}（与 {@code chat-path}/{@code embed-path} 同处一地）；
 *         <b>为什么从 nomic-embed-text 换成 bge-m3</b>（2026-09-22 阶段3 端到端验收实测，一份 587 块的
 *         中文年报）：问"去年利润是多少"时<b>答案所在块在 587 块里排第 27 名</b>、问"归母净利润"排第 47 名
 *         —— 只取 TopK=5，取不到；而 5 条<b>无关</b>块拿到 <b>0.71~0.75</b> 分，连完全无关问题的 top1
 *         也有 0.6929 ⇒ <b>阈值标定救不了</b>（要让无关块出局就得把阈值抬到 0.7 以上，那会把本来命中的
 *         也一起筛掉）。已逐一排除的其它解释：解析（"84.1 亿元"/"8,408,057 千元"都在正文里）、前缀不对称
 *         （{@code cos(库内向量, 带前缀重算) = 1.000000}）、生成链路（换个问法"受益计划服务年限"能答对）、
 *         阈值（一条都没被筛掉）。⇒ 定性为 nomic 在<b>中文细粒度检索</b>上的排序能力不足，
 *         改用中文检索专长的 bge-m3；</li>
 *     <li>{@link #EMBED_BATCH_SIZE}：批量大小（探针 B 实测批量入参被接受）。设计 §3.9 写的是
 *         {@code embed-batch-size: 16}，但 yml 里没有这个键 —— 故先落常量，建议补
 *         {@code nexus.ai.rag.embed-batch-size}；</li>
 *     <li>{@link #EXPECTED_DIMENSION}：1024。它的真源是 {@code db-patch} 里
 *         {@code t_kb_chunk.embedding vector(1024)}（表由
 *         {@code db-patch/202609221100_知识库向量维度改1024.sql} 重建）。本类<b>只做校验</b>
 *         （不符就抛系统异常），不参与建表 —— 建表早于本类运行。
 *         ⚠️ <b>与 {@link #EMBED_MODEL} 必须同改</b>：维度与 DB 的 {@code vector(N)} 是同一条真源。
 *         改模型不改维度 ⇒ 第一次入库就以 PG 类型错的形态现形（错误在事务深处，看着像代码 bug）；
 *         只改维度不改模型 ⇒ 本类的维度校验先抛系统异常。两种都是本类存在的意义。</li>
 * </ul>
 *
 * <h2>日志级别</h2>
 * 每批一条 {@code debug}（设计 §4.11 的"向量化"行）。⚠️ yml 里 {@code com.nexus} 是 info，
 * 要看这行得临时把 {@code com.nexus.module.ai.rag.embedding} 调到 debug
 * （§6.2 只给 {@code rag.mapper} 开了 debug）。<b>不打印正文</b>：日志里只有条数、维度、耗时。
 *
 * @author nexus
 */
@Component
public class OllamaEmbeddingService implements EmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(OllamaEmbeddingService.class);

    /**
     * upstream-model-name：见类注释"三个写死的常量"。
     *
     * <p>2026-09-22 由 {@code nomic-embed-text} 换成 {@code bge-m3}（换模型的实测依据写在类注释里）。
     * <b>它与 {@link #EXPECTED_DIMENSION} 必须同改</b>，也与 {@code db-patch} 里那条重建表的补丁同源。
     * <b>本常量只是"名字"，模型本身得先在 Ollama 里存在</b>：拉取清单在
     * {@code docker-compose/docker-compose.yml} 的 {@code ollama-init} 里（本次未改，属 devops 的交付物）。
     */
    private static final String EMBED_MODEL = "bge-m3";

    /**
     * 单次请求的最大文本条数（分批的粒度）。
     *
     * <p>取 16：探针 B 已实测批量入参被接受，16 条 × 500 字 ≈ 一个 PDF 的十几分之一，
     * 单次请求体量约几十 KB —— 既不像"逐条调用"那样把 HTTP 往返次数翻十几倍
     * （一份 3000 块的文档就是 3000 次往返），也不会造出一个巨大的请求体。
     */
    private static final int EMBED_BATCH_SIZE = 16;

    /**
     * 期望的向量维度 = 表定义 {@code t_kb_chunk.embedding vector(1024)}
     * （表由 {@code db-patch/202609221100_知识库向量维度改1024.sql} 重建）。
     *
     * <p><b>为什么是 1024，以及它的可信度</b>：bge-m3 的<b>公布值</b> —— BAAI/bge-m3 仓库的
     * {@code config.json} 是 {@code hidden_size: 1024}（xlm-roberta 架构，2026-09-22 取自此模型的
     * HuggingFace 镜像），稠密检索取的就是这一维。⚠️ <b>本机没实测过</b>：换模型时
     * {@code nexus-ollama} 里还没有 bge-m3（{@code ollama list} 只有 nomic-embed-text / qwen2.5:7b），
     * 而探针 A 量到的是 nomic 的 768。devops 拉取后请按设计 §3.9 探针 A 复核一次
     * （入参换 {@code {"model":"bge-m3","input":["测试"]}}，数返回数组长度，期望 1024）。
     * 复核不符时的处置是"改本常量 + 改补丁的 vector(N)"，不是改这里去迁就实测值。
     *
     * <p>⚠️ <b>与 {@link #EMBED_MODEL} 同源，必须一起改</b>：这条真源的另一半在 DB 的
     * {@code vector(N)} 上，而维度从 768 变 1024 <b>不能靠 ALTER COLUMN TYPE</b>
     * （列上已有 768 维数据，转换不可行）—— 只能新增补丁重建表，理由见那条补丁的文件头。
     *
     * <p><b>为什么要校验而不是"信任上游"</b>：维度不符时 PostgreSQL 会拒绝插入
     * （报的是类型错误），那条错误出现在事务深处、看起来像代码 bug；而"期望 vs 实际"的
     * 一条日志能把排查方向直接指向"模型换了吗 / 表定义对得上吗"。这就是设计 §9 风险 3
     * 保留的处置：维度仍写进启动期日志 + 逐批校验，将来再换 embedding 模型时第一个发现。
     */
    private static final int EXPECTED_DIMENSION = 1024;

    /** 错误响应体进日志的字节上限（诊断信息，不是用户正文，但也没必要整段搬进日志）。 */
    private static final int ERROR_BODY_SNIPPET_BYTES = 200;

    /** 上游响应里向量数组的字段名。 */
    private static final String FIELD_EMBEDDINGS = "embeddings";

    /** 上游响应里错误报文的字段名。 */
    private static final String FIELD_ERROR = "error";

    private final RestClient restClient;

    private final ObjectMapper objectMapper;

    /** 向量化接口路径（{@code nexus.ai.ollama.embed-path}）。 */
    private final String embedPath;

    /** 入库侧前缀（可为空串 = 关闭；换 bge-m3 后**默认就是空串**，见类注释"前缀在这里加"）。 */
    private final String documentPrefix;

    /** 查询侧前缀（可为空串 = 关闭）。与 {@link #documentPrefix} 成对同源，只改一侧同样不报错。 */
    private final String queryPrefix;

    /**
     * @param aiProperties 网关配置：本类只用 {@code ollama.base-url} / {@code ollama.embed-path}
     *                     与 {@code read-timeout-ms}
     * @param ragProperties RAG 配置：两个检索前缀
     * @param objectMapper 容器里的 Jackson 实例（只用来读树 —— 响应里将来多几个字段不影响本类）
     */
    public OllamaEmbeddingService(AiProperties aiProperties, RagProperties ragProperties, ObjectMapper objectMapper) {
        AiProperties.Ollama ollama = aiProperties.getOllama();
        this.objectMapper = objectMapper;
        this.embedPath = ollama.getEmbedPath();
        this.documentPrefix = nullToEmpty(ragProperties.getDocumentPrefix());
        this.queryPrefix = nullToEmpty(ragProperties.getQueryPrefix());

        // 超时复用 read-timeout-ms（阶段2 既定口径，60s）。**不用** probe-timeout-ms（2000）：
        // 那是探活口径，一份大文档的一批向量化在 CPU 上可能远超 2s，用它就是系统性误杀
        Duration timeout = Duration.ofMillis(aiProperties.getReadTimeoutMs());
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(timeout);
        requestFactory.setReadTimeout(timeout);

        this.restClient = RestClient.builder()
                .requestFactory(requestFactory)
                .baseUrl(ollama.getBaseUrl())
                .build();

        // 启动期把维度与两个前缀打进日志：前者是"换 embedding 模型时第一个发现"（设计 §9 风险 3），
        // 后者让"前缀到底开没开、内容对不对"一眼可见（它配错时**不报错、只是检索变差**）
        log.info("OllamaEmbeddingService 就绪：baseUrl={} embedPath={} model={} dims={} batchSize={} "
                        + "readTimeoutMs={} documentPrefix=[{}] queryPrefix=[{}]",
                ollama.getBaseUrl(), embedPath, EMBED_MODEL, EXPECTED_DIMENSION, EMBED_BATCH_SIZE,
                aiProperties.getReadTimeoutMs(), documentPrefix, queryPrefix);
    }

    @Override
    public List<float[]> embedDocuments(List<String> texts) {
        return embed(texts, documentPrefix);
    }

    @Override
    public float[] embedQuery(String question) {
        // 复用同一条管线（分批、校验、日志），只是前缀不同；单条时必然只有一批
        return embed(List.of(question), queryPrefix).get(0);
    }

    /**
     * 分批向量化。分批是<b>本类的内部实现</b>（端口签名里没有 batch 这个概念）。
     *
     * @param texts  待向量化文本（元素非 null）
     * @param prefix 本方向的任务前缀（可空串）
     * @return 向量列表，顺序与 {@code texts} 一一对应
     * @throws BusinessException 上游不可达 / 超时 / 报错（20100）
     * @throws SystemException   响应条数或维度与预期不符（配置/上游缺陷，非用户问题）
     */
    private List<float[]> embed(List<String> texts, String prefix) {
        if (texts == null || texts.isEmpty()) {
            // 空入参不发请求：一次注定没有结果的上游往返，只会给日志添噪音
            return List.of();
        }
        List<float[]> vectors = new ArrayList<>(texts.size());
        int totalBatches = (texts.size() + EMBED_BATCH_SIZE - 1) / EMBED_BATCH_SIZE;
        int batchIndex = 0;
        for (int from = 0; from < texts.size(); from += EMBED_BATCH_SIZE) {
            int to = Math.min(from + EMBED_BATCH_SIZE, texts.size());
            batchIndex++;
            // subList 是视图（不拷贝）；本方法内只读，安全
            vectors.addAll(embedBatch(texts.subList(from, to), prefix, batchIndex, totalBatches));
        }
        return vectors;
    }

    /**
     * 向量化一批：加前缀 → 发请求 → 校验并解析 → 打一条 debug 日志。
     *
     * @param batch        本批文本（视图，只读）
     * @param prefix       本方向的任务前缀（可空串）
     * @param batchIndex   第几批（1 起，仅用于日志/异常说明）
     * @param totalBatches 共几批
     * @return 本批向量
     */
    private List<float[]> embedBatch(List<String> batch, String prefix, int batchIndex, int totalBatches) {
        List<String> inputs = new ArrayList<>(batch.size());
        for (String text : batch) {
            // 前缀在这里加（D14）：调用方传进来的是"原文"，加了前缀的文本只存在于本方法内
            inputs.add(prefix + text);
        }

        long startNanos = System.nanoTime();
        JsonNode response = requestEmbeddings(inputs, batchIndex, totalBatches);
        List<float[]> vectors = toVectors(response, inputs.size(), batchIndex, totalBatches);

        log.debug("[kb] 向量化: model={} texts={} dims={} durationMs={}",
                EMBED_MODEL, inputs.size(), vectors.get(0).length, elapsedMsSince(startNanos));
        return vectors;
    }

    /**
     * 发一次 embedding 请求，返回解析后的响应树。
     *
     * <p>用 {@code exchange(...)} 而不是 {@code retrieve().body(...)}（与阶段2 两个 provider 同一取舍）：
     * 非 2xx 时要能把<b>上游的错误报文</b>记进日志，而 {@code retrieve()} 把状态码与响应体一起包成异常，
     * 想拿到报文就得靠异常对象上的 getter —— {@code exchange} 则"状态码是判据、报文是诊断信息"，
     * 两者的分工写在代码里。
     *
     * @param inputs       本批文本（已带前缀）
     * @param batchIndex   第几批（1 起）
     * @param totalBatches 共几批
     * @return 响应 JSON 树
     * @throws BusinessException 网络类失败 / 非 2xx / 上游错误报文（20100）
     * @throws SystemException   响应体不是合法 JSON（本服务/上游协议缺陷）
     */
    private JsonNode requestEmbeddings(List<String> inputs, int batchIndex, int totalBatches) {
        UpstreamEmbedRequest requestBody = new UpstreamEmbedRequest(EMBED_MODEL, inputs);
        try {
            return restClient.post()
                    .uri(embedPath)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(requestBody)
                    .exchange((request, response) -> {
                        if (response.getStatusCode().isError()) {
                            // 状态码是判据、报文是诊断信息（不含用户正文，可以进日志）
                            log.warn("Ollama embedding 返回错误状态：status={} path={} batch={}/{} body={}",
                                    response.getStatusCode(), embedPath, batchIndex, totalBatches,
                                    readSnippet(response.getBody()));
                            throw new BusinessException(ResultCode.CHAT_UPSTREAM_UNAVAILABLE);
                        }
                        return readJson(response.getBody(), batchIndex, totalBatches);
                    });
        } catch (ResourceAccessException ex) {
            // 连接被拒 / 读超时 / 上游在响应中途断开：与阶段2 provider 同口径
            log.warn("调用 Ollama embedding 失败：type={} message={} batch={}/{}",
                    ex.getClass().getSimpleName(), ex.getMessage(), batchIndex, totalBatches);
            throw new BusinessException(ResultCode.CHAT_UPSTREAM_UNAVAILABLE);
        } catch (RestClientException ex) {
            // 其余 RestClientException（典型是请求体序列化失败）属**本服务侧缺陷**，不是上游不可用：
            // 原样上抛按 50000 处置（同 AiModelService 类注释里那条分工）
            log.error("调用 Ollama embedding 时出现非网络类失败（按系统异常处置）：type={}",
                    ex.getClass().getName(), ex);
            throw ex;
        }
    }

    /**
     * 把响应体读成 JSON 树。
     *
     * <p><b>包内可见是为了可测</b>（同 {@code OllamaService.parseStream}）：单测可以喂"被 TCP 切碎的"
     * 响应流，断言解析结果，而不必搭一个 HTTP 服务。
     *
     * @param body         响应体（可为 {@code null}）
     * @param batchIndex   第几批（仅用于日志）
     * @param totalBatches 共几批（仅用于日志）
     * @return JSON 树
     * @throws IOException        读流失败（网络类，由调用方的 catch 归到 20100）
     * @throws SystemException    响应体不是合法 JSON，或压根没有响应体
     */
    JsonNode readJson(InputStream body, int batchIndex, int totalBatches) throws IOException {
        if (body == null) {
            log.error("Ollama embedding 返回了空响应体：path={} batch={}/{}", embedPath, batchIndex, totalBatches);
            throw new SystemException("Ollama embedding 返回空响应体（path=" + embedPath + "）");
        }
        try {
            return objectMapper.readTree(body);
        } catch (JsonProcessingException ex) {
            // 报文读不懂 = 上游协议不符，**不是**网络失败：按系统异常处置（50000）。
            // 只记类型与 message（Jackson 的 message 自带内容片段，够定位，且不含用户正文）
            log.error("Ollama embedding 响应体不是合法 JSON：path={} batch={}/{} type={} message={}",
                    embedPath, batchIndex, totalBatches, ex.getClass().getSimpleName(), ex.getMessage());
            throw new SystemException("Ollama embedding 响应体不是合法 JSON（path=" + embedPath + "）", ex);
        }
    }

    /**
     * 把响应树里的 {@code embeddings} 转成向量，并逐条校验形状。
     *
     * <p>校验失败一律<b>抛系统异常</b>（不是 20100、也不是 10203）：这说明"表定义的维度"与
     * "上游模型的输出"对不上，或上游改了协议 —— 属配置/上游缺陷，用户重试多少次都一样。
     * 静默截断则更糟：写入时 PostgreSQL 才报类型错误，而那时错误已经离现场很远了。
     *
     * @param response     响应树
     * @param expectedCount 本批入参条数（期望的向量条数）
     * @param batchIndex    第几批
     * @param totalBatches  共几批
     * @return 向量列表（顺序即上游返回顺序，也是入参顺序）
     * @throws BusinessException 上游错误报文（HTTP 200 + {@code {"error":...}}，20100）
     * @throws SystemException   条数不符 / 维度不符 / 形状不符
     */
    private static List<float[]> toVectors(JsonNode response, int expectedCount, int batchIndex, int totalBatches) {
        if (response.hasNonNull(FIELD_ERROR)) {
            // Ollama 的失败可以是 HTTP 200 + {"error":"..."}（典型：模型没拉取）。
            // 错误文案是上游的诊断信息（如 model 'xxx' not found），不是用户正文，可以进日志
            log.warn("Ollama embedding 返回错误报文：{} batch={}/{}",
                    response.path(FIELD_ERROR).asText(), batchIndex, totalBatches);
            throw new BusinessException(ResultCode.CHAT_UPSTREAM_UNAVAILABLE);
        }

        JsonNode embeddings = response.path(FIELD_EMBEDDINGS);
        if (!embeddings.isArray()) {
            log.error("Ollama embedding 响应缺少 {} 数组：batch={}/{} 顶层字段={}",
                    FIELD_EMBEDDINGS, batchIndex, totalBatches, fieldNames(response));
            throw new SystemException("Ollama embedding 响应形状不符：缺少 " + FIELD_EMBEDDINGS + " 数组");
        }
        if (embeddings.size() != expectedCount) {
            log.error("Ollama embedding 返回条数与入参不符：期望={} 实际={} batch={}/{}",
                    expectedCount, embeddings.size(), batchIndex, totalBatches);
            throw new SystemException("Ollama embedding 返回条数与入参不符：期望 " + expectedCount
                    + " 条，实际 " + embeddings.size() + " 条");
        }

        List<float[]> vectors = new ArrayList<>(expectedCount);
        for (JsonNode item : embeddings) {
            if (!item.isArray()) {
                log.error("Ollama embedding 返回的向量不是数组：batch={}/{}", batchIndex, totalBatches);
                throw new SystemException("Ollama embedding 返回的向量不是数组");
            }
            if (item.size() != EXPECTED_DIMENSION) {
                log.error("Ollama embedding 向量维度与表定义不符：期望={} 实际={} batch={}/{}"
                                + "（对照 t_kb_chunk.embedding 的 vector(1024) 与当前模型 bge-m3）",
                        EXPECTED_DIMENSION, item.size(), batchIndex, totalBatches);
                throw new SystemException("Ollama embedding 向量维度与表定义不符：期望 " + EXPECTED_DIMENSION
                        + " 维，实际 " + item.size() + " 维");
            }
            float[] vector = new float[EXPECTED_DIMENSION];
            for (int i = 0; i < EXPECTED_DIMENSION; i++) {
                // 上游给的是十进制小数，转 float 与本项目"向量用 float[] 承载"的口径一致
                //（落库时由 VectorTypeHandler 拼成 vector 字面量）
                vector[i] = (float) item.get(i).asDouble();
            }
            vectors.add(vector);
        }
        return vectors;
    }

    /**
     * 读取一小段响应体用于日志（错误报文/诊断信息）。
     *
     * <p>只读前 {@link #ERROR_BODY_SNIPPET_BYTES} 字节并压成单行：日志里不该出现多行 JSON 洪水。
     * 截断可能切断一个多字节字符（末尾会显示成一个替换字符）—— 可接受，它只是诊断信息。
     *
     * @param body 响应体，可为 {@code null}
     * @return 单行片段；读不到时给一句说明
     */
    private static String readSnippet(InputStream body) {
        if (body == null) {
            return "(无响应体)";
        }
        try {
            byte[] bytes = body.readNBytes(ERROR_BODY_SNIPPET_BYTES);
            String text = new String(bytes, StandardCharsets.UTF_8)
                    .replace('\n', ' ')
                    .replace('\r', ' ');
            return bytes.length >= ERROR_BODY_SNIPPET_BYTES ? text + "…(截断)" : text;
        } catch (IOException ex) {
            // 连错误报文都读不到：记一句就够，不要在异常路径上再造一个异常
            return "(读取响应体失败：" + ex.getClass().getSimpleName() + ")";
        }
    }

    /**
     * 列出一层 JSON 对象的字段名（只用于"形状不符"那一条日志）。
     *
     * <p>为什么值得这几行：上游改字段名时，日志里出现 {@code 顶层字段=model,embedding}
     * 就能一眼看出"字段名不对"，而不是只剩一句"缺少 embeddings"。
     *
     * @param node 任意节点
     * @return 逗号分隔的字段名；非对象时返回说明文本
     */
    private static String fieldNames(JsonNode node) {
        if (!node.isObject()) {
            return "(非 JSON 对象)";
        }
        StringBuilder builder = new StringBuilder();
        node.fieldNames().forEachRemaining(name -> {
            if (builder.length() > 0) {
                builder.append(", ");
            }
            builder.append(name);
        });
        return builder.toString();
    }

    /**
     * 空值归一：配置里没写（{@code null}）等同于关闭前缀，而不是"null 拼进文本"。
     *
     * @param prefix 配置值，可为 {@code null}
     * @return 非 null 的字符串
     */
    private static String nullToEmpty(String prefix) {
        return prefix == null ? "" : prefix;
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
