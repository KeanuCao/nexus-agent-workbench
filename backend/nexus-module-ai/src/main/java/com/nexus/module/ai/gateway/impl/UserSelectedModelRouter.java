package com.nexus.module.ai.gateway.impl;

import com.nexus.common.exception.BusinessException;
import com.nexus.common.result.ResultCode;
import com.nexus.module.ai.gateway.AiModelFactory;
import com.nexus.module.ai.gateway.ModelRouter;
import com.nexus.module.ai.gateway.ModelType;
import com.nexus.module.ai.gateway.config.AiProperties;
import com.nexus.module.ai.gateway.dto.ChatRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 本轮唯一的路由实现：<b>用户选哪个就调哪个</b>（决策 D6 / D7）。
 *
 * <p>两条规则，没有第三条：
 * <ol>
 *     <li>请求里带了 {@code modelType} → 用它，{@code servedBy = user-selected}；</li>
 *     <li>没带（{@code null} / 空白）→ 取配置的默认模型 {@code nexus.ai.default-model}，
 *         {@code servedBy = default}。</li>
 * </ol>
 *
 * <p><b>本轮刻意不做降级链</b>（决策 D7）：用户选了云端 DeepSeek 而密钥没配时，
 * 如实报 {@code 20100}，<b>不</b>偷偷改用本地 Qwen —— 那会让验收标准 2.2-1
 * （{@code meta.model} 必须是 {@code deepseek-chat}）以"看着跑通了其实是本地模型"的方式
 * <b>假通过</b>。降级链排在自动路由之后（设计 §10.1）。
 *
 * @author nexus
 */
@Component
public class UserSelectedModelRouter implements ModelRouter {

    private static final Logger log = LoggerFactory.getLogger(UserSelectedModelRouter.class);

    private final AiModelFactory modelFactory;

    /** 请求未指定模型时使用的类型；由 {@code nexus.ai.default-model} 解析而来。 */
    private final ModelType defaultModelType;

    public UserSelectedModelRouter(AiModelFactory modelFactory, AiProperties properties) {
        this.modelFactory = modelFactory;
        this.defaultModelType = resolveDefaultModelType(properties.getDefaultModel());
    }

    @Override
    public Decision route(ChatRequest request) {
        String requested = request.getModelType();
        if (requested == null || requested.isBlank()) {
            return defaultDecision();
        }
        // 未知取值在这里抛 BusinessException(10200)。此刻还在请求线程上、尚未开流，
        // 所以它能走正常的 Result 出口 —— 这是"路由必须早于调用上游"的核心理由。
        ModelType type = ModelType.parse(requested);
        return new Decision(modelFactory.provider(type), modelFactory.descriptor(type), ServedBy.USER_SELECTED);
    }

    /**
     * 请求没带 {@code modelType} 时的分支（决策 D6：{@code null} = 交给后端决定）。
     *
     * @return 使用默认模型的决策
     * @throws BusinessException 默认模型也解析不出来（{@code nexus.ai.default-model} 为空）
     */
    private Decision defaultDecision() {
        if (defaultModelType == null) {
            // 唯一能走到这里的形态是配置项被显式置空/空白。不静默兜底成某个模型：
            // "没选模型"变成"随便挑一个"是最难排查的一类问题。
            log.error("请求未指定 modelType，且配置项 nexus.ai.default-model 为空 —— 无法决定用哪个模型");
            throw new BusinessException(ResultCode.CHAT_MODEL_UNSUPPORTED);
        }
        return new Decision(modelFactory.provider(defaultModelType),
                modelFactory.descriptor(defaultModelType), ServedBy.DEFAULT);
    }

    /**
     * 把配置里的默认模型解析成枚举，<b>非法取值让应用启动即失败</b>。
     *
     * <p>为什么要在构造期（而不是第一次用到时）解析：配置写错的失败应当响在启动期。
     * 若推迟到请求期，前端收到的会是"不支持的模型类型"（10200）—— 一个指向<b>请求参数</b>的提示，
     * 而真正的原因在服务端配置里，排查方向会被带偏。
     *
     * @param configured {@code nexus.ai.default-model} 的原始值
     * @return 解析出的类型；配置为空时返回 {@code null}（由 {@link #defaultDecision()} 处置）
     * @throws IllegalStateException 配置取值不在 {@link ModelType} 内
     */
    private static ModelType resolveDefaultModelType(String configured) {
        try {
            ModelType parsed = ModelType.parse(configured);
            if (parsed == null) {
                log.warn("配置项 nexus.ai.default-model 为空 —— 未携带 modelType 的对话请求将被拒绝（10200）");
            }
            return parsed;
        } catch (BusinessException ex) {
            throw new IllegalStateException("配置项 nexus.ai.default-model 取值非法：'" + configured
                    + "'（允许取值：" + ModelType.allowedValues() + "）", ex);
        }
    }
}
