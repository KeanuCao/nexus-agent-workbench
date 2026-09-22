package com.nexus.module.ai.rag.service.impl;

import com.nexus.common.exception.BusinessException;
import com.nexus.common.exception.SystemException;
import com.nexus.common.result.ResultCode;
import com.nexus.infrastructure.tenant.TenantContext;
import com.nexus.module.ai.gateway.ModelType;
import com.nexus.module.ai.rag.config.RagProperties;
import com.nexus.module.ai.rag.dto.KbAnswerVO;
import com.nexus.module.ai.rag.dto.KbAskRequest;
import com.nexus.module.ai.rag.embedding.EmbeddingService;
import com.nexus.module.ai.rag.generate.ModelAnswerGenerator;
import com.nexus.module.ai.rag.mapper.KbChunkMapper;
import com.nexus.module.ai.rag.model.ChunkHit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link KbAskServiceImpl} 单元测试 —— <b>本阶段最值得机器钉住的一条：检索不到就说不知道</b>（设计 §7.1）。
 *
 * <h2>三条判据（各自的缺陷形态都在注释里）</h2>
 * <ol>
 *     <li><b>检索为空（或全被阈值筛掉）⇒ {@code grounded=false} 且 {@code ModelAnswerGenerator} 零调用</b>
 *         ——"检索不到就说不知道"的<b>机器判据</b>。少了它，"检索不到时照样问模型"这件事
 *         只会表现为一次莫名其妙的成本和一个像模像样的幻觉答案；</li>
 *     <li><b>命中 ⇒ {@code sources} 按 score 降序（顺序即 SQL 的 ORDER BY，服务层不重排）
 *         且 {@code generation} 非 null</b>；</li>
 *     <li><b>空答案 ⇒ {@code 20100}</b>：上游"成功但一个字都没给"绝不能被包装成
 *         {@code answer=""} + {@code grounded=true} ——那是个"看着成功其实没答"的响应（设计 §3.7）。</li>
 * </ol>
 *
 * <p>租户上下文用 {@code TenantContext.set(...)} 真造（它是静态 ThreadLocal，本测试跑在自己的线程上）：
 * <b>检索的 {@code tenantId} 参数就是从它取的</b>——那条"人手写对"的退让（检索 SQL 绕开了
 * 租户拦截器）在单测里的投影就是"传下去的必须正好是当前租户"与"没有上下文时必须 fail-closed"。
 *
 * @author nexus
 */
class KbAskServiceImplTest {

    private static final long TENANT_ID = 1L;

    private static final long USER_ID = 11L;

    private EmbeddingService embeddingService;

    private KbChunkMapper chunkMapper;

    private ModelAnswerGenerator generator;

    private RagProperties ragProperties;

    private KbAskServiceImpl service;

    @BeforeEach
    void setUp() {
        embeddingService = mock(EmbeddingService.class);
        chunkMapper = mock(KbChunkMapper.class);
        generator = mock(ModelAnswerGenerator.class);
        ragProperties = new RagProperties();
        service = new KbAskServiceImpl(embeddingService, chunkMapper, generator, ragProperties);

        TenantContext.set(USER_ID, TENANT_ID, "unit-test-jti");
        // 查询向量的维度跟生产的 EXPECTED_DIMENSION 走（2026-09-22 晚 768 → 1024）；本类**不校验维度**，
        // 桩里给对维度只是为了不写出一个会误导人的数字
        when(embeddingService.embedQuery(anyString())).thenReturn(new float[1024]);
    }

    @AfterEach
    void tearDown() {
        // ThreadLocal 必须清（线程池会复用线程 —— 那正是 TenantContext 类注释里最危险的形态）
        TenantContext.clear();
    }

    @Test
    @DisplayName("★ 检索为空 → grounded=false + 固定文案 + sources 空数组 + generation=null，且大模型零调用")
    void shouldShortCircuitWithoutCallingModelWhenNothingRetrieved() {
        when(chunkMapper.search(anyLong(), anyString(), anyInt())).thenReturn(List.of());

        KbAnswerVO answer = service.ask(question("去年利润是多少", null));

        assertFalse(answer.grounded(), "检索为空必须是 grounded=false");
        assertEquals(KbAnswerVO.NO_RESULT_ANSWER, answer.answer(), "answer 用后端常量，前端不自己拼");
        assertNotNull(answer.sources(), "sources 必须是空数组，不是 null");
        assertTrue(answer.sources().isEmpty());
        assertNull(answer.generation(), "没调大模型 ⇒ generation 必须是 null（不是零值对象）");
        assertEquals(0, answer.retrieval().hits());
        assertEquals(ragProperties.getTopK(), answer.retrieval().topK());
        assertEquals(ragProperties.getScoreThreshold(), answer.retrieval().threshold());
        // ★ 本条的核心：一次调用都没有
        verify(generator, never()).generate(anyList());
    }

    @Test
    @DisplayName("★ 命中全低于阈值 → 同上（阈值这一道闸在应用层，SQL 里没有它）")
    void shouldShortCircuitWhenAllHitsBelowThreshold() {
        when(chunkMapper.search(anyLong(), anyString(), anyInt())).thenReturn(List.of(
                hit(7L, "公司年报.pdf", 3, "无关内容甲", 0.31d),
                hit(7L, "公司年报.pdf", 4, "无关内容乙", 0.12d)));

        KbAnswerVO answer = service.ask(question("今天杭州天气怎么样", null));

        assertFalse(answer.grounded());
        assertTrue(answer.sources().isEmpty());
        assertNull(answer.generation());
        assertEquals(0, answer.retrieval().hits(), "hits = 过阈值后的条数（不是 TopK 返回条数）");
        verify(generator, never()).generate(anyList());
    }

    @Test
    @DisplayName("命中一条（恰好等于阈值）→ 保留：阈值是「低于才丢弃」（score >= threshold 留下）")
    void shouldKeepHitExactlyAtThreshold() {
        when(chunkMapper.search(anyLong(), anyString(), anyInt())).thenReturn(List.of(
                hit(7L, "公司年报.pdf", 3, "净利润 84.1 亿元", ragProperties.getScoreThreshold())));
        stubGenerator("净利 84.1 亿元 [资料1]");

        KbAnswerVO answer = service.ask(question("利润", null));

        assertTrue(answer.grounded(), "恰好等于阈值应当保留（边界语义写在实现里，必须被钉住）");
        assertEquals(1, answer.retrieval().hits());
    }

    @Test
    @DisplayName("★ 命中两条 → sources 保持 SQL 的顺序（不重排）、score 保留 4 位小数、generation 非 null")
    void shouldReturnSourcesInScoreOrderWithGeneration() {
        when(chunkMapper.search(anyLong(), anyString(), anyInt())).thenReturn(List.of(
                hit(7L, "公司年报.pdf", 11, "营业总收入 897 亿元。", 0.82415d),
                hit(7L, "公司年报.pdf", 12, "归母净利润 84.1 亿元。", 0.7112d)));
        stubGenerator("根据资料，归母净利润 84.1 亿元 [资料2]。");

        KbAnswerVO answer = service.ask(question("去年利润是多少", 3));

        assertTrue(answer.grounded());
        assertEquals(2, answer.sources().size());
        assertEquals(0.8242d, answer.sources().get(0).score(), "score 保留 4 位小数（HALF_UP）");
        assertEquals(0.7112d, answer.sources().get(1).score());
        assertTrue(answer.sources().get(0).score() >= answer.sources().get(1).score(), "必须保持降序");
        assertEquals(11, answer.sources().get(0).chunkIndex(), "chunkIndex 是 0 起的存储口径（展示时才 +1）");
        assertEquals("营业总收入 897 亿元。", answer.sources().get(0).content(), "引用即原文：不截断、不摘要");
        assertEquals("公司年报.pdf", answer.sources().get(0).fileName());
        assertEquals(3, answer.retrieval().topK(), "请求里带的就是本次生效值");
        assertEquals(2, answer.retrieval().hits());
        assertNotNull(answer.generation(), "命中时必须有生成侧观测块");
        assertEquals(ModelType.DEEPSEEK.name(), answer.generation().modelType());
        assertEquals("deepseek-chat", answer.generation().model());
        assertEquals("根据资料，归母净利润 84.1 亿元 [资料2]。", answer.answer());
    }

    @Test
    @DisplayName("★ 上游成功但答案为空 → 20100（绝不返回「看着成功其实没答」的响应）")
    void shouldFailWith20100WhenAnswerIsBlank() {
        when(chunkMapper.search(anyLong(), anyString(), anyInt()))
                .thenReturn(List.of(hit(7L, "公司年报.pdf", 0, "净利润 84.1 亿元", 0.9d)));
        stubGenerator("");

        BusinessException ex = assertThrows(BusinessException.class, () -> service.ask(question("利润", null)));

        assertEquals(ResultCode.CHAT_UPSTREAM_UNAVAILABLE.getCode(), ex.getCode(),
                "空答案按上游不可用处置（HTTP 503），让用户重试");

        stubGenerator("   \n  ");
        assertThrows(BusinessException.class, () -> service.ask(question("利润", null)),
                "纯空白同样是空答案");
    }

    @Test
    @DisplayName("★ topK：缺省用配置值、显式值生效、越界（0 / 21）→ 40001")
    void shouldResolveTopKWithBoundariesInBusinessLayer() {
        // 先跑越界那两条：它们必须在**任何 IO 之前**失败（否则一次参数错误会白烧一次向量化 + 一次 SQL）
        BusinessException zero = assertThrows(BusinessException.class, () -> service.ask(question("问题", 0)));
        BusinessException tooLarge = assertThrows(BusinessException.class, () -> service.ask(question("问题", 21)));
        assertEquals(ResultCode.PARAM_INVALID.getCode(), zero.getCode());
        assertEquals(ResultCode.PARAM_INVALID.getCode(), tooLarge.getCode());
        verifyNoInteractions(embeddingService);

        when(chunkMapper.search(anyLong(), anyString(), anyInt())).thenReturn(List.of());
        assertEquals(ragProperties.getTopK(), service.ask(question("问题", null)).retrieval().topK(),
                "缺省（null）= 用配置值 top-k");
        assertEquals(1, service.ask(question("问题", 1)).retrieval().topK(), "显式值原样生效");
    }

    @Test
    @DisplayName("问题：空 / 全空白 / 超长（按字符计）→ 40001；首尾空白被 trim 后再交给向量化")
    void shouldValidateQuestionAndTrimItBeforeEmbedding() {
        when(chunkMapper.search(anyLong(), anyString(), anyInt())).thenReturn(List.of());

        assertEquals(ResultCode.PARAM_INVALID.getCode(),
                assertThrows(BusinessException.class, () -> service.ask(question("   ", null))).getCode());
        assertEquals(ResultCode.PARAM_INVALID.getCode(),
                assertThrows(BusinessException.class, () -> service.ask(question(null, null))).getCode());
        assertEquals(ResultCode.PARAM_INVALID.getCode(),
                assertThrows(BusinessException.class,
                        () -> service.ask(question("问".repeat(501), null))).getCode());

        // 边界：恰好 500 字符（含首尾空白）→ trim 后 498 字，合法
        service.ask(question(" " + "问".repeat(498) + " ", null));
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(embeddingService).embedQuery(captor.capture());
        assertEquals("问".repeat(498), captor.getValue(), "首尾空白不占上游上下文，trim 后再向量化");
    }

    @Test
    @DisplayName("★ 传给检索的 tenantId 来自 TenantContext，且 topK 原样传下去（检索隔离的单测投影）")
    void shouldPassCurrentTenantToSearch() {
        when(chunkMapper.search(anyLong(), anyString(), anyInt())).thenReturn(List.of());
        TenantContext.set(USER_ID, 2L, "another-jti");

        service.ask(question("问题", 7));

        ArgumentCaptor<String> vectorCaptor = ArgumentCaptor.forClass(String.class);
        verify(chunkMapper).search(eq(2L), vectorCaptor.capture(), eq(7));
        assertTrue(vectorCaptor.getValue().startsWith("["), "查询向量的形态是 [0.1,0.2,...] 字面量");
    }

    @Test
    @DisplayName("★ 无租户上下文 → 系统异常（fail-closed：绝不退化成不限租户的查询）")
    void shouldFailClosedWithoutTenantContext() {
        TenantContext.clear();

        assertThrows(SystemException.class, () -> service.ask(question("问题", null)));

        verify(chunkMapper, never()).search(anyLong(), anyString(), anyInt());
        verify(generator, never()).generate(anyList());
    }

    @Test
    @DisplayName("检索耗时含问题向量化：retrieval.durationMs 覆盖向量化 + SQL 两段（契约 §7.6 口径）")
    void shouldMeasureRetrievalIncludingEmbedding() throws InterruptedException {
        when(chunkMapper.search(anyLong(), anyString(), anyInt())).thenAnswer(invocation -> {
            // 用一次小睡把"SQL 耗时"变成可观测的量，确认计时窗口不早于它结束
            Thread.sleep(30L);
            return List.of();
        });
        when(embeddingService.embedQuery(anyString())).thenAnswer(invocation -> {
            Thread.sleep(20L);
            return new float[1024];
        });

        KbAnswerVO answer = service.ask(question("问题", null));

        assertTrue(answer.retrieval().durationMs() >= 50L,
                "durationMs 必须包住向量化(≥20ms) + 检索(≥30ms)，实际=" + answer.retrieval().durationMs());
    }

    /** 造一个请求（{@code topK} 传 null 表示"用配置值"）。 */
    private static KbAskRequest question(String text, Integer topK) {
        KbAskRequest request = new KbAskRequest();
        request.setQuestion(text);
        request.setTopK(topK);
        return request;
    }

    /** 让假生成器回一段固定文本。 */
    private void stubGenerator(String text) {
        when(generator.generate(any())).thenReturn(new ModelAnswerGenerator.GeneratedAnswer(
                text, ModelType.DEEPSEEK, "deepseek-chat", "stop", 1234L));
    }

    /** 造一条检索命中（字段顺序即 SQL 选出的形状）。 */
    private static ChunkHit hit(long documentId, String fileName, int chunkIndex, String content, double score) {
        return new ChunkHit(documentId, fileName, chunkIndex, content, content.length(), score);
    }
}
