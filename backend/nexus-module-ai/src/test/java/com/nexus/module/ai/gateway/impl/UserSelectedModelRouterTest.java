package com.nexus.module.ai.gateway.impl;

import com.nexus.common.exception.BusinessException;
import com.nexus.common.result.ResultCode;
import com.nexus.module.ai.gateway.AiModelFactory;
import com.nexus.module.ai.gateway.AiModelRegistry;
import com.nexus.module.ai.gateway.AiModelService;
import com.nexus.module.ai.gateway.ModelDescriptor;
import com.nexus.module.ai.gateway.ModelRouter;
import com.nexus.module.ai.gateway.ModelType;
import com.nexus.module.ai.gateway.config.AiProperties;
import com.nexus.module.ai.gateway.dto.ChatRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link UserSelectedModelRouter} 单元测试 —— 两条规则、两种失败。
 *
 * <p>它是决策 D6 的行为判据："{@code modelType} 有值就用它、{@code null} 就取默认"，
 * 以及 {@code servedBy} 到底回给前端哪一个取值（前端的模型下拉据此显示"自动/指定"）。
 *
 * <p>装配走的是真实的 {@link AiModelRegistry} + {@link AiModelFactory}（只把 provider 换成一个伪造实现），
 * 而不是把 {@code AiModelFactory} 也 mock 掉：这样"注册表按 {@code descriptor().type()} 建索引"
 * 这条链路也一并被覆盖 —— 它恰好是"传 OLLAMA 却命中 DeepSeek"这类事故的唯一发生地。
 *
 * @author nexus
 */
class UserSelectedModelRouterTest {

    private ModelRouter router;

    private AiModelService ollamaProvider;

    @BeforeEach
    void setUp() {
        ollamaProvider = mock(AiModelService.class);
        when(ollamaProvider.descriptor()).thenReturn(new ModelDescriptor(
                ModelType.OLLAMA, "qwen2.5:7b", true, 32_768, ModelDescriptor.CostTier.FREE));

        @SuppressWarnings("unchecked")
        ObjectProvider<AiModelService> providers = mock(ObjectProvider.class);
        when(providers.orderedStream()).thenReturn(Stream.of(ollamaProvider));

        router = newRouter(new AiModelFactory(new AiModelRegistry(providers)), new AiProperties());
    }

    @Test
    @DisplayName("请求带了 modelType：用它，servedBy=user-selected")
    void shouldUseRequestedModel() {
        ModelRouter.Decision decision = router.route(requestWith("OLLAMA"));

        assertSame(ollamaProvider, decision.service());
        assertEquals("qwen2.5:7b", decision.descriptor().modelName());
        assertEquals(ModelRouter.ServedBy.USER_SELECTED, decision.servedBy());
        assertEquals("user-selected", decision.servedBy().getWireValue());
    }

    @Test
    @DisplayName("modelType 为 null：取默认模型，servedBy=default（D6 的行为判据）")
    void shouldFallBackToDefaultModelWhenAbsent() {
        ModelRouter.Decision decision = router.route(requestWith(null));

        assertSame(ollamaProvider, decision.service());
        assertEquals(ModelRouter.ServedBy.DEFAULT, decision.servedBy());
        assertEquals("default", decision.servedBy().getWireValue());
    }

    @Test
    @DisplayName("modelType 为空白：同样按「未指定」处理（不是未知取值）")
    void shouldTreatBlankAsUnspecified() {
        assertEquals(ModelRouter.ServedBy.DEFAULT, router.route(requestWith("   ")).servedBy());
    }

    @Test
    @DisplayName("未知取值：BusinessException(10200) —— 请求线程上抛出，走正常 Result 出口")
    void shouldRejectUnknownModelType() {
        BusinessException ex = assertThrows(BusinessException.class, () -> router.route(requestWith("GPT-5")));

        assertEquals(ResultCode.CHAT_MODEL_UNSUPPORTED.getCode(), ex.getCode());
    }

    @Test
    @DisplayName("默认模型配置被写错：构造期即失败（fail-fast），不拖到第一个请求才报 10200")
    void shouldFailFastWhenDefaultModelConfigIsInvalid() {
        AiProperties properties = new AiProperties();
        properties.setDefaultModel("gpt-5");

        assertThrows(IllegalStateException.class,
                () -> newRouter(new AiModelFactory(new AiModelRegistry(emptyProviders())), properties));
    }

    @Test
    @DisplayName("默认模型配置为空：不静默兜底成某个模型，未指定 modelType 的请求按 10200 拒绝")
    void shouldRejectUnspecifiedRequestWhenDefaultModelIsBlank() {
        AiProperties properties = new AiProperties();
        properties.setDefaultModel("");

        ModelRouter blankDefaultRouter = newRouter(
                new AiModelFactory(new AiModelRegistry(emptyProviders())), properties);

        BusinessException ex = assertThrows(BusinessException.class, () -> blankDefaultRouter.route(requestWith(null)));
        assertEquals(ResultCode.CHAT_MODEL_UNSUPPORTED.getCode(), ex.getCode());
    }

    private static ModelRouter newRouter(AiModelFactory factory, AiProperties properties) {
        return new UserSelectedModelRouter(factory, properties);
    }

    private static ObjectProvider<AiModelService> emptyProviders() {
        @SuppressWarnings("unchecked")
        ObjectProvider<AiModelService> providers = mock(ObjectProvider.class);
        when(providers.orderedStream()).thenReturn(Stream.empty());
        return providers;
    }

    private static ChatRequest requestWith(String modelType) {
        ChatRequest request = new ChatRequest();
        request.setModelType(modelType);
        return request;
    }
}
