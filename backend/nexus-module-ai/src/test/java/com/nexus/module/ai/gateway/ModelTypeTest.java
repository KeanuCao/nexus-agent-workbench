package com.nexus.module.ai.gateway;

import com.nexus.common.exception.BusinessException;
import com.nexus.common.result.ResultCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link ModelType} 单元测试 —— 契约 §6.1 里 {@code modelType} 的三条边界。
 *
 * <p>重点不是"能解析"，而是<b>解析失败时抛的是什么</b>：task.3 的验收标准 2.2 点名要求
 * "未知类型抛业务异常"（HTTP 200 + 10200），而不是落进兜底变成 500 + 50000。
 * 后者会同时毁掉两件事：前端拿不到可展示的文案，运维看到的是一条没有任何价值的堆栈。
 *
 * @author nexus
 */
class ModelTypeTest {

    @Test
    @DisplayName("OLLAMA / DEEPSEEK 解析成对应枚举")
    void shouldParseKnownValues() {
        assertEquals(ModelType.OLLAMA, ModelType.parse("OLLAMA"));
        assertEquals(ModelType.DEEPSEEK, ModelType.parse("DEEPSEEK"));
    }

    @Test
    @DisplayName("null / 空串 / 纯空白 → null（= 交给后端决定，决策 D6，不是错误）")
    void shouldReturnNullForUnspecified() {
        assertNull(ModelType.parse(null));
        assertNull(ModelType.parse(""));
        assertNull(ModelType.parse("   "));
    }

    @Test
    @DisplayName("前后空白会被 trim 掉（契约取值本身大小写敏感，但不该被空格绊倒）")
    void shouldTrimSurroundingWhitespace() {
        assertEquals(ModelType.OLLAMA, ModelType.parse(" OLLAMA "));
    }

    @Test
    @DisplayName("大小写不同即视为未知取值（刻意不做宽容，见 parse 的注释）")
    void shouldRejectLowercaseValue() {
        BusinessException ex = assertThrows(BusinessException.class, () -> ModelType.parse("ollama"));

        assertEquals(ResultCode.CHAT_MODEL_UNSUPPORTED.getCode(), ex.getCode());
    }

    @Test
    @DisplayName("未知取值 → BusinessException(10200)，文案可展示")
    void shouldRejectUnknownValue() {
        BusinessException ex = assertThrows(BusinessException.class, () -> ModelType.parse("GPT-5"));

        assertEquals(ResultCode.CHAT_MODEL_UNSUPPORTED.getCode(), ex.getCode());
        assertEquals(ResultCode.CHAT_MODEL_UNSUPPORTED.getMsg(), ex.getMessage());
    }

    @Test
    @DisplayName("allowedValues() 是日志与提示文案的取值清单（顺序即枚举声明顺序）")
    void shouldDescribeAllowedValues() {
        assertEquals("OLLAMA, DEEPSEEK", ModelType.allowedValues());
    }

    @Test
    @DisplayName("providerKey 是日志里的 provider=xxx（设计 §4.5 的日志格式依赖它）")
    void shouldExposeLowerCaseProviderKey() {
        assertEquals("ollama", ModelType.OLLAMA.getProviderKey());
        assertEquals("deepseek", ModelType.DEEPSEEK.getProviderKey());
    }
}
