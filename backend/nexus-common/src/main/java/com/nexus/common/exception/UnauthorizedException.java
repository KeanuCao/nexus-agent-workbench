package com.nexus.common.exception;

import com.nexus.common.result.ResultCode;

import java.io.Serial;

/**
 * 未认证异常：请求没有（或不再有）合法的登录态，HTTP 出口为 <b>401</b>。
 *
 * <p>与 {@link BusinessException} 并列而非继承它，原因是一个明确的取舍
 * （docs/design/01-多租户与认证.md 决策 D6）：
 * <ul>
 *     <li>{@link BusinessException} 的契约是「HTTP <b>200</b> + 非 0 业务码」，前端拦截器按
 *         {@code code} 判定并在**成功分支**里弹 msg；置失败原因于 200 是刻意的，目的是不让
 *         业务失败污染 axios 的失败分支。</li>
 *     <li>而「未登录 / 登录态失效」是<b>传输层的认证语义</b>，前端 {@code request.ts} 的 401
 *         分支已经预留了「清 token + 跳登录」的钩子 —— 契约在先。若让本异常继承
 *         {@code BusinessException}，一旦有人写出 {@code catch (BusinessException)} 兜底，
 *         401 会被静默降级成 200，登录跳转随之失效。</li>
 * </ul>
 *
 * <p>因此本类直接继承 {@link RuntimeException}，与 {@code BusinessException} 处于同一层，
 * 由 {@code GlobalExceptionHandler} 单独出口（HTTP 401 + warn 级别、不打堆栈）。
 *
 * @author nexus
 */
public class UnauthorizedException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 响应码（40100 / 40101 / 40102 之一），见 {@link ResultCode}。 */
    private final int code;

    /**
     * 使用响应码枚举构造（码与文案均来自枚举）。
     *
     * @param resultCode 响应码枚举，约定取 {@link ResultCode#UNAUTHENTICATED} /
     *                   {@link ResultCode#TOKEN_INVALID} / {@link ResultCode#TOKEN_EXPIRED}
     */
    public UnauthorizedException(ResultCode resultCode) {
        this(resultCode.getCode(), resultCode.getMsg());
    }

    /**
     * 使用自定义码与文案构造。
     *
     * @param code 响应码（40100 / 40101 / 40102）
     * @param msg  可展示给前端的提示信息
     */
    public UnauthorizedException(int code, String msg) {
        super(msg);
        this.code = code;
    }

    public int getCode() {
        return code;
    }
}
