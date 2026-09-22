package com.nexus.module.ai.rag.service.impl;

import com.nexus.common.exception.BusinessException;
import com.nexus.common.exception.SystemException;
import com.nexus.common.result.ResultCode;
import com.nexus.infrastructure.tenant.TenantContext;
import com.nexus.module.ai.gateway.dto.ChatMessage;
import com.nexus.module.ai.rag.config.RagProperties;
import com.nexus.module.ai.rag.dto.KbAnswerVO;
import com.nexus.module.ai.rag.dto.KbAskRequest;
import com.nexus.module.ai.rag.dto.KbSourceVO;
import com.nexus.module.ai.rag.embedding.EmbeddingService;
import com.nexus.module.ai.rag.generate.ModelAnswerGenerator;
import com.nexus.module.ai.rag.mapper.KbChunkMapper;
import com.nexus.module.ai.rag.mapper.VectorTypeHandler;
import com.nexus.module.ai.rag.model.ChunkHit;
import com.nexus.module.ai.rag.prompt.PromptBuilder;
import com.nexus.module.ai.rag.service.KbAskService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * {@link KbAskService} 的实现：检索 → 拼 → 生成（设计 §4.9 的四条纪律）。
 *
 * <h2>顺序不能换（日志就是按这个顺序读的）</h2>
 * <pre>
 * 问题向量化 → 检索 SQL → 阈值过滤（应用层）→ 检索日志（hits/maxScore/threshold/durationMs）
 *   → 空？ 是 → 固定文案 + grounded=false，**不调大模型**（并记一条"未调用大模型"的日志）
 *          否 → PromptBuilder → ModelAnswerGenerator → 答案
 * </pre>
 * 设计 §4.11 的"生成完成"那一行由 {@code ModelAnswerGenerator} 自己打（它手里有
 * provider/model/finishReason/chars/durationMs）—— 本类<b>不重复打</b>：它要的 {@code sources=N}
 * 与上面检索行的 {@code hits=N} 是同一个数（都是过阈值后的命中条数），两行相邻就是全部信息。
 *
 * <h2>四条纪律（逐条对应设计 §4.9）</h2>
 * <ol>
 *     <li><b>空结果短路不调模型</b>：这是"检索不到就说不知道"的<b>结构性保证</b>，也是本阶段
 *         省成本最直接的一处；</li>
 *     <li><b>{@code score} 由 SQL 算出</b>（{@code 1 - (embedding <=> q)}），本类只做比较与格式化，
 *         <b>不在 Java 里重算一遍距离</b> —— 重算等于有两份"相似度"的定义，且必然与库里那份漂移；</li>
 *     <li><b>引用即原文</b>：{@code sources[].content} 用分块原文，不截断、不摘要；</li>
 *     <li><b>两个观测块必须有</b>：{@code retrieval} / {@code generation} 是"答案不对"时
 *         唯一可见的定位证据。</li>
 * </ol>
 *
 * <h2>租户隔离的形态在这里与别处不同（一处必要的退让）</h2>
 * 检索语句的租户条件<b>由本类显式传入</b>（{@code chunkMapper.search(tenantId, ...)}），
 * 而不是由 {@code TenantLineHandler} 注入 —— 因为 {@code <=>} 让 MP 的 jsqlparser 解析失败，
 * 拦截器会直接抛异常（实测；完整证据见 {@code KbChunkMapper.search} 的 javadoc）。
 * 传值来自 {@code TenantContext}，无上下文时 fail-closed。
 * ⚠️ 这条退让把"结构化保证"降级成"人手写对"，所以 TC-03 必须有一条
 * <b>"B 租户提问不得命中 A 租户文档"</b>的用例兜住它。
 *
 * <h2>上游"成功但一个字都没给"按 20100 失败（本类的一处明确处置）</h2>
 * {@code ModelAnswerGenerator.generate(...)} 会把"上游正常结束、但累积文本为空"如实返回
 * （它只在日志里留一条 warn）。本类<b>不</b>把它变成 {@code answer=""} + {@code grounded=true}
 * 的"成功"响应：那会造出一个<b>看着成功其实没答</b>的响应，而它的前端处置与
 * "检索不到"（{@code grounded=false}）长得几乎一样，会把排查方向带偏。宁可整体失败
 * （{@code 20100}）让用户重试 —— 重试成本几秒钟，误诊成本半小时（设计 §3.7 的同一条推理）。
 *
 * @author nexus
 */
@Service
public class KbAskServiceImpl implements KbAskService {

    private static final Logger log = LoggerFactory.getLogger(KbAskServiceImpl.class);

    private final EmbeddingService embeddingService;

    private final KbChunkMapper chunkMapper;

    private final ModelAnswerGenerator modelAnswerGenerator;

    private final RagProperties ragProperties;

    /**
     * @param embeddingService     向量化端口（查询侧前缀由实现内部处理）
     * @param chunkMapper          检索 SQL（{@code KbChunkMapper.search}）
     * @param modelAnswerGenerator 生成适配器（复用阶段2 的流式端口，累积成整段答案）
     * @param ragProperties        RAG 配置：{@code top-k} / {@code max-top-k} / {@code score-threshold} /
     *                             {@code max-question-length}
     */
    public KbAskServiceImpl(EmbeddingService embeddingService,
                            KbChunkMapper chunkMapper,
                            ModelAnswerGenerator modelAnswerGenerator,
                            RagProperties ragProperties) {
        this.embeddingService = embeddingService;
        this.chunkMapper = chunkMapper;
        this.modelAnswerGenerator = modelAnswerGenerator;
        this.ragProperties = ragProperties;
    }

    @Override
    public KbAnswerVO ask(KbAskRequest request) {
        String question = resolveQuestion(request);
        int topK = resolveTopK(request.getTopK());
        long tenantId = resolveTenantId();
        double threshold = ragProperties.getScoreThreshold();

        // ① + ② 向量化与检索。两者一起计时：契约 §7.6 把 retrieval.durationMs 定义为
        //        "检索耗时（含问题的向量化）"—— 那一步是检索的固有成本，拆开报反而少一半信息
        long retrievalStartNanos = System.nanoTime();
        float[] queryVector = embeddingService.embedQuery(question);
        // 检索的租户条件**由本方法传入**（不是拦截器注入）：`<=>` 让 jsqlparser 解析失败，
        // 故 KbChunkMapper.search 标了 @InterceptorIgnore 并手写 tenant_id ——
        // 完整的实测证据与代价见该方法的 javadoc。这里从 TenantContext 取值是这条退路上的一环
        List<ChunkHit> hits = chunkMapper.search(tenantId, VectorTypeHandler.toVectorLiteral(queryVector), topK);
        long retrievalMs = elapsedMsSince(retrievalStartNanos);

        // ③ 阈值过滤在**应用层**做：下推成 SQL 的 WHERE 会让 HNSW 索引失效（设计 §4.6 第 2 条）。
        //    maxScore 取的是**过滤前**的最高分：检索为空时它必须能报出一个"最高才 0.31、离阈值差多远"
        //    的数字 —— 那正是阈值标定（设计 §3.9）要看的量
        double maxScore = 0.0;
        List<ChunkHit> kept = new ArrayList<>(hits.size());
        for (ChunkHit hit : hits) {
            if (hit.score() > maxScore) {
                maxScore = hit.score();
            }
            if (hit.score() >= threshold) {
                kept.add(hit);
            }
        }
        // 顺序即 SQL 的 ORDER BY（越相似越靠前），此处**不重排**
        log.info("[kb] 检索: questionLength={} topK={} hits={} maxScore={} threshold={} durationMs={}",
                question.length(), topK, kept.size(), KbSourceVO.roundScore(maxScore),
                KbSourceVO.roundScore(threshold), retrievalMs);

        if (kept.isEmpty()) {
            // 第二道闸：连大模型都不调。刻意单独一条日志 —— 排查时"没调模型"这个事实必须一眼可见，
            // 而不是靠"生成完成"那行没出现去推断
            log.info("[kb] 检索为空（未调用大模型）: questionLength={} topK={} maxScore={} threshold={}",
                    question.length(), topK, KbSourceVO.roundScore(maxScore), KbSourceVO.roundScore(threshold));
            return KbAnswerVO.noResult(topK, threshold, retrievalMs);
        }

        // ④ 拼 Prompt（system 指令 + user 资料/问题）→ 生成
        List<ChatMessage> messages = PromptBuilder.build(question, kept);
        ModelAnswerGenerator.GeneratedAnswer generated = modelAnswerGenerator.generate(messages);

        String answer = generated.text();
        if (answer == null || answer.isBlank()) {
            // 见类注释："成功但没答"必须失败，不能混进 grounded=true 的成功响应里
            log.warn("[kb] 生成结果为空，按上游不可用处置（20100）: provider={} model={} sources={} "
                            + "finishReason={} durationMs={}",
                    generated.modelType().getProviderKey(), generated.modelName(), kept.size(),
                    generated.finishReason(), generated.durationMs());
            throw new BusinessException(ResultCode.CHAT_UPSTREAM_UNAVAILABLE);
        }

        List<KbSourceVO> sources = kept.stream().map(KbSourceVO::from).toList();
        KbAnswerVO.Generation generation = new KbAnswerVO.Generation(
                // modelType 用枚举名（契约 §7.6 的取值就是 OLLAMA / DEEPSEEK）—— 不是 providerKey（小写）
                generated.modelType().name(),
                generated.modelName(),
                generated.durationMs());
        return new KbAnswerVO(answer, true, sources,
                new KbAnswerVO.Retrieval(topK, kept.size(), threshold, retrievalMs), generation);
    }

    /**
     * 取并校验问题（契约 §7.4：非空、去空白后非空、≤ {@code max-question-length}（按<b>字符</b>）。
     *
     * <p>长度按 {@code trim()} 之后算：首尾空白不占用上游上下文，判它没有意义（"600 个空格 + 一句话"
     * 不该被拒绝）。
     *
     * @param request 请求
     * @return 去首尾空白后的问题
     * @throws BusinessException 为空或超长（40001）
     */
    private String resolveQuestion(KbAskRequest request) {
        String question = request.getQuestion();
        if (question == null || question.isBlank()) {
            // 控制器上的 @NotBlank 已经挡了一层（那条走框架的 40001）。这里是防御性的第二层：
            // Service 端口可以被别的调用方直接调用，不能假设入参一定过了 Bean Validation
            throw paramInvalid("问题不能为空");
        }
        String trimmed = question.trim();
        int maxLength = ragProperties.getMaxQuestionLength();
        if (trimmed.length() > maxLength) {
            log.warn("[kb] 问题超长: questionLength={} max={}", trimmed.length(), maxLength);
            throw paramInvalid("问题过长，最多 " + maxLength + " 个字符");
        }
        return trimmed;
    }

    /**
     * 解析本次生效的 topK（缺省 {@code null} = 配置值）。
     *
     * <p><b>边界判在业务侧</b>而不是用 {@code @Min/@Max}：契约要的是 {@code 40001} + <b>可读文案</b>，
     * 而 Bean Validation 的默认消息是英文的（{@code must be less than or equal to 20}），
     * 会被全局异常处理器直接弹给用户（契约 §7.4 第 1 条）。
     *
     * @param requested 请求里的 topK，可为 {@code null}
     * @return 本次生效的 topK
     * @throws BusinessException 越界（40001）
     */
    private int resolveTopK(Integer requested) {
        if (requested == null) {
            return ragProperties.getTopK();
        }
        int maxTopK = ragProperties.getMaxTopK();
        if (requested < 1 || requested > maxTopK) {
            log.warn("[kb] topK 越界: topK={} 允许范围=1..{}", requested, maxTopK);
            throw paramInvalid("topK 取值范围为 1~" + maxTopK);
        }
        return requested;
    }

    /**
     * 取当前租户 ID —— <b>检索隔离的唯一依据</b>（见类注释与 {@code KbChunkMapper.search} 的 javadoc）。
     *
     * <p><b>为什么在这里 fail-closed</b>：这条链路的租户条件不再由拦截器注入，而是一个参数。
     * 若没有上下文还继续往下走，SQL 会拼出 {@code tenant_id = NULL}（匹配不到任何行）——
     * 那是"静默返回空结果"，表现与"知识库里真没有"完全一样，会把排查方向带偏。
     * 抛系统异常（500 + 堆栈 + 服务端日志）才对：那是编码/装配缺陷，不是用户可以纠正的输入。
     *
     * @return 当前租户 ID
     * @throws SystemException 无租户上下文（鉴权链没跑起来，或有人绕过过滤器直接调 Service）
     */
    private static long resolveTenantId() {
        Long tenantId = TenantContext.getTenantId();
        if (tenantId == null) {
            throw new SystemException("知识库检索缺少租户上下文（fail-closed）："
                    + "本链路的租户条件由调用方显式传入，绝不退化成不限租户的查询");
        }
        return tenantId;
    }

    /**
     * 构造参数类业务异常：<b>码取自枚举、文案换成带上下文的</b>。
     *
     * <p>为什么不用 {@code new BusinessException(ResultCode.PARAM_INVALID)}（枚举自带的通用文案）：
     * 那两句提示（"请求参数不合法"）在"topK 越界"这种场景下等于没说，
     * 而这正是契约特意把这两条边界从 Bean Validation 挪到业务侧的原因。
     *
     * @param detail 可展示的具体原因
     * @return 业务异常（code = 40001）
     */
    private static BusinessException paramInvalid(String detail) {
        return new BusinessException(ResultCode.PARAM_INVALID.getCode(), detail);
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
