package com.nexus.module.ai.rag.generate;

import com.nexus.common.exception.SystemException;
import com.nexus.module.ai.gateway.AiModelFactory;
import com.nexus.module.ai.gateway.AiModelService;
import com.nexus.module.ai.gateway.ModelDescriptor;
import com.nexus.module.ai.gateway.ModelType;
import com.nexus.module.ai.gateway.dto.ChatMessage;
import com.nexus.module.ai.rag.config.RagProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 把阶段2 的<b>流式</b>网关端口适配成"一次性拿回整段答案"（决策 D5 的第三个组件）。
 *
 * <h2>为什么是新增一个适配器，而不是给 {@code AiModelService} 加个同步方法</h2>
 * 那个端口的签名是回调式、带取消语义的（阶段2 刻意钉死了"流何时被消费"这个问题）；
 * 为 RAG 加一个 {@code generate(...)} 等于把它重新打开。另写一套 DeepSeek 客户端则是重复实现。
 * 40 行的适配器是最小改动，且天然复用阶段2 的超时口径、异常约定（{@code 20100}）与日志风格。
 *
 * <h2>{@link AiModelService.CancelToken} 传一个"不会被取消"的实例</h2>
 * 本链路是<b>同步</b>的：请求线程一路走到底，没有"客户端断开就取消上游"的通道
 * （那条通道要等到流式问答，见设计 §10.4）。刻意<b>不</b>去监听请求线程中断来支持取消 ——
 * 那是一条当前没有消费者的复杂度：真正的断连只会让 Tomcat 在最后写响应时失败，
 * 而上游那时早已经答完。传 {@code new CancelToken()} 既是"占位"，也是明确写下"本链路不取消"。
 *
 * <h2>异常：一个都不 catch</h2>
 * <ul>
 *     <li>{@link com.nexus.common.exception.BusinessException}（{@code 20100}，上游不可达 / 超时 / 报错）
 *         <b>原样上抛</b>：本链路全在请求线程上，异常能走到全局处理器 → HTTP <b>503</b> + {@code 20100}
 *         ——这正是设计 §3.7 要的"宁可整体失败，让用户重试"；</li>
 *     <li>其余异常（程序缺陷）落兜底 → 500 + {@code 50000}，与"上游真的挂了"在日志里分得开。</li>
 * </ul>
 * 阶段2 之所以要就地 catch，是因为那时 {@code text/event-stream} 的响应头已经发出、异常没有出口；
 * 本类没有这个约束，多一层 catch 只会让两种失败重新混在一起。
 *
 * <h2>日志（设计 §4.11 的"生成完成"行）</h2>
 * 与设计逐字对齐，只少了一段 {@code sources=N}：那个数不在本方法签名里（服务层的检索日志已经打过
 * {@code hits=N}，是同一个数）。不打印答案正文。
 *
 * @author nexus
 */
@Component
public class ModelAnswerGenerator {

    private static final Logger log = LoggerFactory.getLogger(ModelAnswerGenerator.class);

    private final AiModelFactory modelFactory;

    private final RagProperties ragProperties;

    /**
     * @param modelFactory   阶段2 的模型工厂（按类型取 provider 与能力自述）
     * @param ragProperties  RAG 配置：本类只用 {@code answer-model-type}
     */
    public ModelAnswerGenerator(AiModelFactory modelFactory, RagProperties ragProperties) {
        this.modelFactory = modelFactory;
        this.ragProperties = ragProperties;
    }

    /**
     * 调一次模型、拿回整段答案（内部把流式分片累积起来）。
     *
     * <p><b>阻塞</b>直到上游结束。调用方（{@code KbAskServiceImpl}）在请求线程上调用它，
     * 因此这一次调用会占住一个 Tomcat 请求线程数秒 —— 那正是决策 D7/D6 的取舍。
     *
     * @param messages 完整的提示消息（{@code PromptBuilder} 产出：system 指令 + user 资料/问题）
     * @return 整段答案 + 生成侧观测值
     * @throws com.nexus.common.exception.BusinessException 上游不可达 / 超时 / 报错（20100，出口为 HTTP 503）
     * @throws SystemException 配置缺陷（{@code answer-model-type} 为空）
     */
    public GeneratedAnswer generate(List<ChatMessage> messages) {
        ModelType type = resolveModelType();
        // 工厂在"该类型没有已注册实现"时给出 10200 与"已注册清单"的日志，本类不重复判断
        AiModelService provider = modelFactory.provider(type);
        ModelDescriptor descriptor = modelFactory.descriptor(type);

        Accumulator accumulator = new Accumulator();
        long startNanos = System.nanoTime();
        provider.stream(messages, descriptor, accumulator::onChunk, new AiModelService.CancelToken());
        long durationMs = elapsedMsSince(startNanos);

        String text = accumulator.text.toString();
        if (text.isBlank()) {
            // 上游"成功"却一个字都没给。这里只记一条 warn（本类不做失败判定），
            // **真正的处置在 KbAskServiceImpl**：它看到空答案会抛 20100（HTTP 503），
            // 由用户重试。理由：绝不返回"看着成功其实没答"的响应 —— 那与本项目对
            // "答案为空"与"检索不到"（grounded=false）的区分原则直接冲突（设计 §3.7 / §4.7）。
            // 本类保留这条 warn 是因为它离上游最近：provider / finishReason 在这里才可见。
            log.warn("[kb] 生成结束但答案为空: provider={} model={} finishReason={} durationMs={}",
                    type.getProviderKey(), descriptor.modelName(), accumulator.finishReason, durationMs);
        }

        log.info("[kb] 生成完成: provider={} model={} finishReason={} chars={} durationMs={}",
                type.getProviderKey(), descriptor.modelName(), accumulator.finishReason, text.length(), durationMs);

        return new GeneratedAnswer(text, type, descriptor.modelName(), accumulator.finishReason, durationMs);
    }

    /**
     * 解析问答用哪个模型（{@code nexus.ai.rag.answer-model-type}，取值即 {@link ModelType} 枚举名）。
     *
     * @return 模型类型（非 null）
     * @throws SystemException 配置为空 —— 见方法内注释
     */
    private ModelType resolveModelType() {
        String configured = ragProperties.getAnswerModelType();
        ModelType type = ModelType.parse(configured);
        if (type == null) {
            // ModelType.parse 把 null/空白当作"由后端决定"—— 那是**请求侧**的语义（契约 §6.1：
            // 不传 modelType = 交给后端）。配置里没有这个自由度：空值就是配置缺陷，
            // 直接按系统异常喊出来，而不是悄悄兜一个默认模型（兜默认会让"配置写错了"
            // 以"答案来自另一个模型"的形态出现，极难倒查）
            log.error("nexus.ai.rag.answer-model-type 为空，无法确定问答用哪个模型");
            throw new SystemException("nexus.ai.rag.answer-model-type 未配置（取值应为 "
                    + ModelType.allowedValues() + "）");
        }
        return type;
    }

    /**
     * 分片累积器：一次调用一个实例。
     *
     * <p><b>为什么不做成字段</b>：本类是单例 bean，{@code generate(...)} 可以被并发调用 ——
     * 把"已累积的文本"存进字段会让两问的答案互相串味（同 {@code DeepSeekService.ParseState} 的取舍）。
     *
     * @author nexus
     */
    private static final class Accumulator {

        /** 累积的答案正文。 */
        private final StringBuilder text = new StringBuilder();

        /** 上游给出的结束原因（{@code stop} / {@code length}）；未给出时保持 {@code null}。 */
        private String finishReason;

        /**
         * 消费一个分片。
         *
         * <p>按 {@code AiModelService} 的约定，{@code content} 为空串表示"这一拍没吐字"
         * （结束片就是这样），直接跳过。
         *
         * @param chunk 分片
         */
        private void onChunk(AiModelService.Chunk chunk) {
            if (chunk.content() != null && !chunk.content().isEmpty()) {
                text.append(chunk.content());
            }
            if (chunk.finishReason() != null) {
                // 记录但<b>不</b>提前结束：回调式端口没有返回值的语义，实现本来就会在结束片后返回。
                // 记下来是为了进日志 —— length 说明答案被 token 上限截断，那是要看见的信号
                finishReason = chunk.finishReason();
            }
        }
    }

    /**
     * 一次生成的结果（风格对齐 {@code AiModelService.Chunk}）。
     *
     * <p>{@code finishReason} 不进契约（{@code KbAnswerVO.generation} 只有
     * {@code modelType/model/durationMs}）：它的消费者是日志与将来的"答案被截断"提示，
     * 前端不需要知道。
     *
     * @param text         模型生成的整段答案（可能为空串，见 {@code generate} 里的 warn）
     * @param modelType    实际使用的模型类型（来自配置）
     * @param modelName    上游真实模型名（如 {@code deepseek-chat}），回给前端的 {@code generation.model}
     * @param finishReason 上游结束原因（{@code stop} / {@code length}）；
     *                     provider 一个结束片都没给时为 {@code null}（异常形态，日志里能看见）
     * @param durationMs   本次生成的耗时（毫秒）
     * @author nexus
     */
    public record GeneratedAnswer(
            String text,
            ModelType modelType,
            String modelName,
            String finishReason,
            long durationMs) {
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
