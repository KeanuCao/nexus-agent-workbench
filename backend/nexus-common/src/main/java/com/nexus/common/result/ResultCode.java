package com.nexus.common.result;

/**
 * 统一响应码。
 *
 * <p>契约（docs/design/00-环境与部署.md §5.3）：{@code code = 0} 表示成功，非 0 表示失败。
 *
 * <p>编码分段约定（阶段0 只落地实际用到的 4 个，阶段1 按模块细化业务码，不复用本枚举已有值）：
 * <ul>
 *     <li>0：成功</li>
 *     <li>1xxxx：通用业务异常（可展示给前端）</li>
 *     <li>2xxxx：依赖/服务可用性异常（如健康检查探活失败）</li>
 *     <li>4xxxx：请求侧问题（如资源/路径不存在；参数校验等阶段1 补充）</li>
 *     <li>5xxxx：系统异常（不对外暴露细节，仅记日志）</li>
 * </ul>
 *
 * @author nexus
 */
public enum ResultCode {

    /** 成功。msg 固定为 {@code success}，与设计文档 §5.3 契约逐字对齐。 */
    SUCCESS(0, "success"),

    /** 通用业务失败：由 {@link com.nexus.common.exception.BusinessException} 默认使用。 */
    BUSINESS_ERROR(10000, "业务处理失败"),

    /** 请求的资源/路径不存在：404 出口使用（见 GlobalExceptionHandler）。 */
    NOT_FOUND(40400, "请求的资源不存在"),

    /** 依赖服务不可用：健康检查探活失败（HTTP 503）时使用。 */
    SERVICE_UNAVAILABLE(20000, "依赖服务不可用"),

    /** 系统异常：兜底码，对外只给通用话术，细节记服务端日志。 */
    SYSTEM_ERROR(50000, "系统繁忙，请稍后重试");

    private final int code;

    private final String msg;

    ResultCode(int code, String msg) {
        this.code = code;
        this.msg = msg;
    }

    public int getCode() {
        return code;
    }

    public String getMsg() {
        return msg;
    }
}
