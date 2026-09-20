package com.nexus.module.ai.gateway;

import com.nexus.common.exception.BusinessException;
import com.nexus.common.result.ResultCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * 模型工厂：按 {@link ModelType} 取到可用的 provider 实现（任务 2.2 的"工厂模式动态切换"）。
 *
 * <p><b>它与 {@link AiModelRegistry} 的分工</b>（刻意分开，不是重复）：
 * <ul>
 *     <li>注册表是<b>台账</b>：装配、去重、暴露已注册清单，查不到就返回空 —— 它不认识业务码；</li>
 *     <li>工厂是<b>契约出口</b>：把"查不到"翻译成契约规定的 {@code 10200}，
 *         并留下排查用的日志。判定失败该怎么表达，只在这一个地方决定。</li>
 * </ul>
 *
 * <p>调用它的 {@code ModelRouter} 运行在<b>请求线程</b>上、开流之前，所以这里抛出的
 * {@link BusinessException} 能正常走 {@code Result} 出口（HTTP 200 + 10200）——
 * 一旦 emitter 交给了容器，同样的失败就只能写成 {@code event: error} 帧了。
 *
 * @author nexus
 */
@Component
public class AiModelFactory {

    private static final Logger log = LoggerFactory.getLogger(AiModelFactory.class);

    private final AiModelRegistry registry;

    public AiModelFactory(AiModelRegistry registry) {
        this.registry = registry;
    }

    /**
     * 取某个类型的 provider 实现。
     *
     * @param type 模型类型，不可为 {@code null}
     * @return provider 实现
     * @throws BusinessException 该类型没有已注册的实现（code = 10200）
     */
    public AiModelService provider(ModelType type) {
        Objects.requireNonNull(type, "type 不能为空 —— 路由必须给出明确的模型类型");
        return registry.findService(type).orElseThrow(() -> {
            // 对用户而言，"这个类型没有实现"与"传了个未知取值"是同一件事（都用不了），
            // 故复用 10200、让前端文案保持一致；真正的排查线索在下面这行日志里 ——
            // 它带上"已注册清单"，一眼能看出是"没装配上来"还是"装配成了别的类型"。
            log.warn("模型类型 {} 没有已注册的实现，已注册：{}", type, registry.registeredTypes());
            return new BusinessException(ResultCode.CHAT_MODEL_UNSUPPORTED);
        });
    }

    /**
     * 取某个类型的能力自述（模型名等，用于 {@code meta} 帧与日志）。
     *
     * @param type 模型类型，不可为 {@code null}
     * @return 能力自述
     * @throws BusinessException 该类型没有已注册的实现（code = 10200）
     */
    public ModelDescriptor descriptor(ModelType type) {
        Objects.requireNonNull(type, "type 不能为空 —— 路由必须给出明确的模型类型");
        return registry.findDescriptor(type).orElseThrow(() -> {
            log.warn("模型类型 {} 没有已注册的能力自述，已注册：{}", type, registry.registeredTypes());
            return new BusinessException(ResultCode.CHAT_MODEL_UNSUPPORTED);
        });
    }
}
