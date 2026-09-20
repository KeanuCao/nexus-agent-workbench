package com.nexus.module.ai.gateway;

import com.nexus.common.exception.BusinessException;
import com.nexus.common.result.ResultCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 模型类型：既是路由的输入，也是 {@code event: meta} 帧回给前端的 {@code modelType}。
 *
 * <p>枚举名即线上取值（{@code OLLAMA} / {@code DEEPSEEK}，见 {@code docs/api/README.md} §6.2）——
 * 刻意不做"枚举名与线上取值分离"（如写 {@code LOCAL} 再映射成 {@code OLLAMA}）：
 * 契约里的取值就是实现里的枚举名，少一层映射就少一处对不上的地方。
 * 附带各 provider 的日志标识 {@link #getProviderKey()}（小写），只为日志可读性
 * （设计 §4.5 的日志格式是 {@code provider=ollama}），不参与任何判定。
 *
 * @author nexus
 */
public enum ModelType {

    /** 本地 Ollama（{@code qwen2.5:7b}），零 API 成本。 */
    OLLAMA("ollama"),

    /** 云端 DeepSeek（{@code deepseek-chat}），需要 {@code DEEPSEEK_API_KEY}。 */
    DEEPSEEK("deepseek");

    private static final Logger log = LoggerFactory.getLogger(ModelType.class);

    /** 允许取值的可读文本，只用于日志/异常说明（**不进响应体**：契约固定的 msg 是"不支持的模型类型"）。 */
    private static final String ALLOWED_VALUES = buildAllowedValues();

    /** 日志标识（小写 provider 名）。 */
    private final String providerKey;

    ModelType(String providerKey) {
        this.providerKey = providerKey;
    }

    /**
     * 把请求里的 {@code modelType} 字符串转成枚举。
     *
     * <p>三条边界，都是契约（{@code docs/api/README.md} §6.1）：
     * <ul>
     *     <li>{@code null} / 空白 → 返回 {@code null}。这<b>不是错误</b>：决策 D6 规定
     *         "不传 modelType = 交给后端决定"，由 {@code ModelRouter} 补默认模型；</li>
     *     <li>取值不在枚举内 → 抛 {@code BusinessException(10200)}（HTTP 200 出口，
     *         不返回堆栈）—— task.3 验收标准 2.2 点名要求"未知类型抛业务异常"；</li>
     *     <li><b>大小写敏感</b>：契约枚举就是 {@code OLLAMA}/{@code DEEPSEEK}，
     *         {@code ollama} 同样按未知取值处理。刻意不做大小写宽容 —— 宽容会让
     *         "契约里枚举之外的值也能用"成为既成事实，前端一旦依赖它，收紧就是破坏性变更。</li>
     * </ul>
     *
     * @param raw 请求体里的原始字符串，可为 {@code null} / 空白
     * @return 对应枚举；{@code null} 表示"由后端决定"
     * @throws BusinessException 取值未知（code = {@link ResultCode#CHAT_MODEL_UNSUPPORTED}）
     */
    public static ModelType parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String normalized = raw.trim();
        for (ModelType type : values()) {
            if (type.name().equals(normalized)) {
                return type;
            }
        }
        // 先记原始取值再抛：10200 的出口日志只有 code 与固定文案，不带取值 ——
        // 少了这一行，"谁传错了"在日志里查不出来，只能靠猜。
        log.warn("未知的 modelType：raw={} 允许取值={}", normalized, ALLOWED_VALUES);
        throw new BusinessException(ResultCode.CHAT_MODEL_UNSUPPORTED);
    }

    /**
     * 允许取值的可读文本，形如 {@code OLLAMA, DEEPSEEK}。
     *
     * @return 枚举名列表（供日志与配置校验的提示信息使用）
     */
    public static String allowedValues() {
        return ALLOWED_VALUES;
    }

    /**
     * 日志/装配用的 provider 标识（小写）。
     *
     * @return 如 {@code ollama}
     */
    public String getProviderKey() {
        return providerKey;
    }

    private static String buildAllowedValues() {
        StringBuilder builder = new StringBuilder();
        for (ModelType type : values()) {
            if (builder.length() > 0) {
                builder.append(", ");
            }
            builder.append(type.name());
        }
        return builder.toString();
    }
}
