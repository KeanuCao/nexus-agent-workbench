package com.nexus.common.exception;

import com.nexus.common.result.ResultCode;

import java.io.Serial;

/**
 * 业务异常：可预期的、由业务规则拒绝的请求。
 *
 * <p>与 {@link SystemException} 的区别（CLAUDE.md 宪法约束）：
 * <ul>
 *     <li>业务异常：{@code msg} 是可以直接展示给前端的文案（如"用户名或密码错误"），
 *         全局异常处理器按 warn 级别记录，不打堆栈、不记 error；</li>
 *     <li>系统异常：{@code msg} 只进日志，前端统一收到通用话术。</li>
 * </ul>
 *
 * <p>用法示例：
 * <pre>
 * if (user == null) {
 *     throw new BusinessException("用户名或密码错误");
 * }
 * </pre>
 *
 * @author nexus
 */
public class BusinessException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 业务响应码（非 0），默认取 {@link ResultCode#BUSINESS_ERROR}。 */
    private final int code;

    /**
     * 使用默认业务码构造。
     *
     * @param msg 可展示给前端的提示信息
     */
    public BusinessException(String msg) {
        this(ResultCode.BUSINESS_ERROR.getCode(), msg);
    }

    /**
     * 使用响应码枚举构造（码与文案均来自枚举）。
     *
     * @param resultCode 响应码枚举，不可为 {@link ResultCode#SUCCESS}
     */
    public BusinessException(ResultCode resultCode) {
        this(resultCode.getCode(), resultCode.getMsg());
    }

    /**
     * 使用自定义业务码构造。
     *
     * @param code 业务响应码（非 0）
     * @param msg  可展示给前端的提示信息
     */
    public BusinessException(int code, String msg) {
        super(msg);
        this.code = code;
    }

    public int getCode() {
        return code;
    }
}
