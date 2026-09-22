package com.nexus.module.ai.rag.embedding;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.common.exception.BusinessException;
import com.nexus.common.exception.SystemException;
import com.nexus.common.result.ResultCode;
import com.nexus.module.ai.gateway.config.AiProperties;
import com.nexus.module.ai.rag.config.RagProperties;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpExchange;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link OllamaEmbeddingService} 单元测试 —— <b>喂固定响应体、断言"认不认得出坏报文"</b>（设计 §7.1）。
 *
 * <h2>为什么用一个本地 HTTP 桩，而不是只测解析函数</h2>
 * 本类真正会咬人的三件事<b>都在 HTTP 这一层</b>，只测 {@code readJson} 是测不到的：
 * <ol>
 *     <li><b>非 2xx 与"HTTP 200 + {@code {"error":...}}"两种失败形态</b>都要落成 {@code 20100}
 *         —— 前者在 {@code exchange} 回调里判状态码，后者在响应树里找 {@code error} 字段；</li>
 *     <li><b>条数与维度校验必须在写入数据库之前发生</b>：条数不符时静默截断的后果是"某一批少了几块"，
 *         而检索侧只表现为"某段查不到"（极难倒查）；维度不符时 PostgreSQL 会在事务深处报类型错误，
 *         看起来像代码 bug。两者都必须是<b>系统异常</b>（50000），不是 20100、也不是静默通过；</li>
 *     <li><b>前缀真的加在了文本上</b>（决策 D14）：改前缀必须重灌数据，而"加没加、加得对不对"
 *         只有看发出去的请求体才知道 —— 这一层是它唯一的观测点。</li>
 * </ol>
 * 桩用 JDK 自带的 {@code com.sun.net.httpserver.HttpServer}（绑定 127.0.0.1 的<b>随机空闲端口</b>）：
 * 不引任何新依赖、不碰外网、不碰 11434 上的真 Ollama（那是 TC-03 的实机用例的领地）。
 *
 * <p>⚠️ 需要 Mockito 的地方一处都没有 —— 本类的协作者是 HTTP，不是接口。
 *
 * @author nexus
 */
class OllamaEmbeddingServiceTest {

    /** 表定义维度（{@code t_kb_chunk.embedding vector(768)}，探针 A 实测）—— 桩必须按它造向量。 */
    private static final int DIMENSION = 768;

    /** 一个 768 维向量在 JSON 里的样子（复用同一份字面量：它很长，不该在断言里拼第二遍）。 */
    private static final String VECTOR_768 = vectorJson(0.1d);

    private HttpServer server;

    /** 桩收到的请求体（按到达顺序）；本类只用它做"发了什么"的观察。 */
    private final List<String> requestBodies = Collections.synchronizedList(new ArrayList<>());

    /** 桩要回的响应体；{@code null} = 按入参条数自动回同样多的 768 维向量（正常路径的默认行为）。 */
    private volatile String stubResponse;

    /** 桩要回的向量条数；{@code null} = 与入参条数相同（造"条数不符"时显式给一个不同的值）。 */
    private volatile Integer stubVectorCount;

    /** 桩要回的 HTTP 状态码。 */
    private volatile int stubStatus = 200;

    /** 桩被调用的次数（"空入参不发请求"这类断言看它）。 */
    private final AtomicInteger requestCount = new AtomicInteger();

    private RagProperties ragProperties;

    private OllamaEmbeddingService service;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/embed", this::handle);
        server.start();

        AiProperties aiProperties = new AiProperties();
        // 指向桩：127.0.0.1 + 内核分配的随机端口（避免与真 Ollama 的 11434 或其它测试撞端口）
        aiProperties.getOllama().setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        aiProperties.getOllama().setEmbedPath("/api/embed");

        ragProperties = new RagProperties();
        service = new OllamaEmbeddingService(aiProperties, ragProperties, new ObjectMapper());
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    @DisplayName("★ 批量：17 条文本 → 2 次请求（16 + 1），返回 17 个 768 维向量，且带入库侧前缀")
    void shouldEmbedInBatchesWithDocumentPrefix() {
        List<String> texts = new ArrayList<>();
        for (int i = 0; i < 17; i++) {
            texts.add("第 " + i + " 块");
        }

        List<float[]> vectors = service.embedDocuments(texts);

        assertEquals(17, vectors.size(), "返回条数必须与入参一一对应");
        for (float[] vector : vectors) {
            assertEquals(DIMENSION, vector.length);
        }
        assertEquals(2, requestCount.get(), "17 条按每批 16 条切：应为 2 次请求");
        assertEquals(16, occurrences(requestBodies.get(0), "search_document: "),
                "第 1 批 16 条，每条都要带入库侧前缀（决策 D14）");
        assertEquals(1, occurrences(requestBodies.get(1), "search_document: "));
        assertTrue(requestBodies.get(0).contains("\"model\":\"nomic-embed-text\""),
                "请求体里的模型名必须是 embedding 模型，不是对话模型 qwen2.5:7b");
    }

    @Test
    @DisplayName("★ 查询侧前缀与入库侧不同（search_query / search_document 成对同源）")
    void shouldUseQueryPrefixForQueryEmbedding() {
        float[] vector = service.embedQuery("去年利润是多少");

        assertEquals(DIMENSION, vector.length);
        assertEquals(1, requestCount.get(), "单条查询只发一次请求");
        assertTrue(requestBodies.get(0).contains("\"input\":[\"search_query: 去年利润是多少\"]"),
                "查询必须加 search_query 前缀（且只加一次），实际请求体：" + requestBodies.get(0));
    }

    @Test
    @DisplayName("空入参不发请求（一次注定没有结果的上游往返只会给日志添噪音）")
    void shouldNotCallUpstreamForEmptyInput() {
        assertTrue(service.embedDocuments(List.of()).isEmpty());
        assertEquals(0, requestCount.get());
    }

    @Test
    @DisplayName("★ 条数不符 → 系统异常（不是静默截断、也不是 20100）")
    void shouldFailWithSystemExceptionWhenCountMismatch() {
        // 入参 2 条，上游只回 1 条
        stubVectorCount = 1;

        SystemException ex = assertThrows(SystemException.class,
                () -> service.embedDocuments(List.of("甲", "乙")));

        assertTrue(ex.getMessage().contains("条数"), "异常文案要写明是条数问题，实际：" + ex.getMessage());
    }

    @Test
    @DisplayName("★ 维度与表定义不符 → 系统异常（768 维是 t_kb_chunk.embedding 的真源）")
    void shouldFailWithSystemExceptionWhenDimensionMismatch() {
        // 767 维：探针 A 说过模型给 768，这里造一个"模型换了但表没改"的形态
        stubResponse = "{\"embeddings\":[" + vectorJson(new double[767]) + "]}";

        SystemException ex = assertThrows(SystemException.class,
                () -> service.embedDocuments(List.of("甲")));

        assertTrue(ex.getMessage().contains("768"), "文案要带'期望 vs 实际'，实际：" + ex.getMessage());
    }

    @Test
    @DisplayName("响应缺 embeddings 数组 → 系统异常（含顶层字段名的日志是排查依据）")
    void shouldFailWithSystemExceptionWhenEmbeddingsMissing() {
        stubResponse = "{\"model\":\"nomic-embed-text\"}";

        assertThrows(SystemException.class, () -> service.embedDocuments(List.of("甲")));
    }

    @Test
    @DisplayName("★ HTTP 200 + {\"error\":...}（模型没拉取）→ 20100")
    void shouldFailWith20100OnUpstreamErrorPayload() {
        stubResponse = "{\"error\":\"model 'nomic-embed-text' not found, try pulling it first\"}";

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.embedDocuments(List.of("甲")));

        assertEquals(ResultCode.CHAT_UPSTREAM_UNAVAILABLE.getCode(), ex.getCode());
    }

    @Test
    @DisplayName("★ 非 2xx（500）→ 20100（与阶段2 provider 同口径：上游不可用）")
    void shouldFailWith20100OnNon2xxStatus() {
        stubStatus = 500;
        stubResponse = "{\"error\":\"internal server error\"}";

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.embedDocuments(List.of("甲")));

        assertEquals(ResultCode.CHAT_UPSTREAM_UNAVAILABLE.getCode(), ex.getCode());
    }

    @Test
    @DisplayName("响应体不是 JSON → 系统异常（上游协议不符属本服务/上游缺陷，不是网络故障）")
    void shouldFailWithSystemExceptionWhenBodyIsNotJson() {
        stubResponse = "这不是 JSON";

        assertThrows(SystemException.class, () -> service.embedDocuments(List.of("甲")));
    }

    @Test
    @DisplayName("响应体为空（null 流）→ 系统异常")
    void shouldFailWithSystemExceptionWhenBodyIsNull() {
        assertThrows(SystemException.class, () -> service.readJson(null, 1, 1));
    }

    /**
     * 桩的处理器：记录请求体，按 {@link #stubStatus} / {@link #stubResponse} 回一个响应。
     *
     * <p>默认按入参条数回同样多的向量 —— 本类有"每批 16 条"的分批逻辑，桩若固定回一条，
     * 一条正常路径的用例就会以"条数不符"失败（那不是被测对象的缺陷）。
     *
     * @param exchange 一次 HTTP 交换
     */
    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            requestCount.incrementAndGet();
            String requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requestBodies.add(requestBody);

            byte[] body = (stubResponse != null ? stubResponse : autoResponse(requestBody))
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(stubStatus, body.length);
            exchange.getResponseBody().write(body);
        }
    }

    /**
     * 按请求体里的 {@code input} 条数造一个正常响应（{@link #stubVectorCount} 可覆盖条数）。
     *
     * @param requestBody 桩收到的请求体
     * @return 形如 {@code {"model":...,"embeddings":[[...],...]}} 的响应体
     */
    private String autoResponse(String requestBody) throws IOException {
        int inputCount = new ObjectMapper().readTree(requestBody).path("input").size();
        int vectorCount = stubVectorCount == null ? inputCount : stubVectorCount;
        StringBuilder builder = new StringBuilder("{\"model\":\"nomic-embed-text\",\"embeddings\":[");
        for (int i = 0; i < vectorCount; i++) {
            if (i > 0) {
                builder.append(',');
            }
            builder.append(VECTOR_768);
        }
        return builder.append("]}").toString();
    }

    /** 造一个维度为 {@code dimension} 的向量 JSON 字面量（每个分量由下标决定，便于人眼核对长度）。 */
    private static String vectorJson(int dimension) {
        double[] values = new double[dimension];
        for (int i = 0; i < dimension; i++) {
            values[i] = (i % 10) / 10d;
        }
        return vectorJson(values);
    }

    /** 造一个分量全部相同的向量 JSON 字面量。 */
    private static String vectorJson(double value) {
        StringBuilder builder = new StringBuilder(2 + DIMENSION * 6);
        builder.append('[');
        for (int i = 0; i < DIMENSION; i++) {
            if (i > 0) {
                builder.append(',');
            }
            builder.append(value);
        }
        return builder.append(']').toString();
    }

    private static String vectorJson(double[] values) {
        StringBuilder builder = new StringBuilder();
        builder.append('[');
        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                builder.append(',');
            }
            builder.append(values[i]);
        }
        return builder.append(']').toString();
    }

    /** 数一段文本里出现了几次某个子串（不用 split，避免正则与空串的边角行为）。 */
    private static int occurrences(String text, String needle) {
        int count = 0;
        int from = 0;
        while (true) {
            int index = text.indexOf(needle, from);
            if (index < 0) {
                return count;
            }
            count++;
            from = index + needle.length();
        }
    }
}
