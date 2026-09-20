package com.nexus.module.ai.gateway;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 模型注册表：{@code ModelType → (实现 + 能力自述)}，启动时一次性装配。
 *
 * <p>它是"有哪些模型可用"的<b>唯一台账</b>：实现和自述从同一处取出，不在两个地方各存一份，
 * 也就不会出现"取到的实现与取到的模型名不是一对"。
 *
 * <h2>装配方式：注入全部 {@link AiModelService}，按自述建立索引</h2>
 * provider 实现是被 Spring 扫到的普通组件，不需要谁去 registry 里登记 ——
 * <b>新增一个模型 = 新增一个实现类</b>，注册表零改动。这正是"工厂 + 策略"要的效果。
 *
 * <h2>为什么注入的是 {@link ObjectProvider} 而不是 {@code List<AiModelService>}</h2>
 * 注入 {@code List} 时，若容器里一个候选都没有，Spring 会抛
 * {@code NoSuchBeanDefinitionException}（"expected at least 1 bean which qualifies as
 * autowire candidate"）—— <b>应用直接起不来</b>。而"一个 provider 都还没装配上"是一种
 * 真实会出现的形态：阶段2 分两段实施（公共层先落地）、或将来某个实现类被临时摘掉。
 * 用 ObjectProvider 让后端此时照常启动（只打一条 warn），
 * 代价是"漏配实现"从启动失败变成运行期可见的 10200 —— 后者有明确的错误码与日志，
 * 前者是整栈起不来（连 /api/health 都取不到，排查方向会被带偏到环境问题上）。
 *
 * <h2>重复声明即启动失败</h2>
 * 两个实现自称同一 {@code ModelType} 属配置缺陷：注册表静默取其一的话，
 * 表现是"改了代码但模型名没变"，而这类"改了没生效"正是本仓库吃过亏的失效形态
 * （见 {@code docs/design/00-环境与部署.md} §5.1）。故在构造期直接抛异常。
 *
 * @author nexus
 */
@Component
public class AiModelRegistry {

    private static final Logger log = LoggerFactory.getLogger(AiModelRegistry.class);

    private final Map<ModelType, AiModelService> services;

    private final Map<ModelType, ModelDescriptor> descriptors;

    public AiModelRegistry(ObjectProvider<AiModelService> providers) {
        // EnumMap：键是枚举，天然有序（按声明顺序）且无哈希开销 —— 日志与遍历输出都稳定可断言
        Map<ModelType, AiModelService> serviceMap = new EnumMap<>(ModelType.class);
        Map<ModelType, ModelDescriptor> descriptorMap = new EnumMap<>(ModelType.class);

        for (AiModelService provider : providers.orderedStream().toList()) {
            ModelDescriptor descriptor = provider.descriptor();
            if (descriptor == null) {
                throw new IllegalStateException("模型实现 " + provider.getClass().getName()
                        + " 的 descriptor() 返回 null —— 能力自述是注册表的键，不可为空");
            }
            AiModelService previous = serviceMap.put(descriptor.type(), provider);
            if (previous != null) {
                throw new IllegalStateException("模型类型 " + descriptor.type() + " 被两个实现同时声明："
                        + previous.getClass().getName() + " 与 " + provider.getClass().getName()
                        + " —— 请让其中一个改 descriptor().type()，否则总是其中一个被静默忽略");
            }
            descriptorMap.put(descriptor.type(), descriptor);
        }

        this.services = Collections.unmodifiableMap(serviceMap);
        this.descriptors = Collections.unmodifiableMap(descriptorMap);

        if (serviceMap.isEmpty()) {
            log.warn("模型注册表装配完成：**没有任何模型实现**。当前后端可正常启动，"
                    + "但任何一次对话都会以 10200（不支持的模型类型）失败 —— "
                    + "通常是 provider 实现尚未加入（阶段2 的 OllamaService / DeepSeekService）");
            return;
        }
        log.info("模型注册表装配完成：{}", describeRegisteredModels());
    }

    /**
     * 查实现。
     *
     * @param type 模型类型，不可为 {@code null}
     * @return 对应的 provider 实现；未注册时为空
     */
    public Optional<AiModelService> findService(ModelType type) {
        Objects.requireNonNull(type, "type 不能为空 —— 路由必须给出明确的模型类型");
        return Optional.ofNullable(services.get(type));
    }

    /**
     * 查能力自述。
     *
     * @param type 模型类型，不可为 {@code null}
     * @return 对应的能力自述；未注册时为空
     */
    public Optional<ModelDescriptor> findDescriptor(ModelType type) {
        Objects.requireNonNull(type, "type 不能为空 —— 路由必须给出明确的模型类型");
        return Optional.ofNullable(descriptors.get(type));
    }

    /**
     * 已注册的类型（只读，按枚举声明顺序）。
     *
     * @return 已注册的模型类型集合；可能为空（表示 provider 实现尚未装配）
     */
    public Set<ModelType> registeredTypes() {
        return services.keySet();
    }

    private String describeRegisteredModels() {
        StringBuilder builder = new StringBuilder();
        for (Map.Entry<ModelType, ModelDescriptor> entry : descriptors.entrySet()) {
            if (builder.length() > 0) {
                builder.append(", ");
            }
            builder.append(entry.getKey().getProviderKey()).append('=')
                    .append(entry.getValue().modelName());
        }
        return builder.length() == 0 ? "(空)" : builder.toString();
    }
}
