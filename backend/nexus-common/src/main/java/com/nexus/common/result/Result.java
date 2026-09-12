package com.nexus.common.result;

import java.io.Serial;
import java.io.Serializable;

/**
 * 统一响应体：所有 API 一律返回 {@code Result<T>}（CLAUDE.md 宪法约束）。
 *
 * <p>JSON 形态恒为三个字段，顺序固定：
 * <pre>
 * { "code": 0, "msg": "success", "data": { ... } }
 * </pre>
 *
 * <p>注意：本类刻意不提供 {@code isSuccess()} 之类的方法 ——
 * Bean 序列化会把 {@code isXxx()} / {@code getXxx()} 方法暴露为 JSON 字段，
 * 多出的 {@code success} 字段会破坏前端按 {@code Result} 解包的契约。
 * 判定成功请直接用 {@link #getCode()} 与 {@link ResultCode#SUCCESS} 比较。
 *
 * <p>本类位于 nexus-common，不得引入任何第三方依赖（Jackson / Lombok 均不允许），
 * 序列化行为完全由上层模块的框架配置决定。
 *
 * @param <T> 业务数据类型（无数据时为 {@code Void}，JSON 中表现为 {@code null}）
 * @author nexus
 */
public class Result<T> implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 响应码：0 成功，非 0 失败（见 {@link ResultCode}）。 */
    private int code;

    /** 提示信息：成功固定为 {@code success}；失败时为可展示给前端的文案。 */
    private String msg;

    /** 业务数据：失败时为 {@code null}。 */
    private T data;

    public Result() {
        // 供 Jackson 反序列化使用（前端联调 / 测试构造响应体时可能需要）
    }

    public Result(int code, String msg, T data) {
        this.code = code;
        this.msg = msg;
        this.data = data;
    }

    /**
     * 成功响应（无数据）。
     *
     * @param <T> 数据类型
     * @return {@code code = 0}、{@code data = null} 的响应体
     */
    public static <T> Result<T> success() {
        return success(null);
    }

    /**
     * 成功响应。
     *
     * @param data 业务数据，可为 {@code null}
     * @param <T>  数据类型
     * @return {@code code = 0} 的响应体
     */
    public static <T> Result<T> success(T data) {
        return new Result<>(ResultCode.SUCCESS.getCode(), ResultCode.SUCCESS.getMsg(), data);
    }

    /**
     * 成功响应（自定义提示信息）。
     *
     * @param msg  提示信息
     * @param data 业务数据，可为 {@code null}
     * @param <T>  数据类型
     * @return {@code code = 0} 的响应体
     */
    public static <T> Result<T> success(String msg, T data) {
        return new Result<>(ResultCode.SUCCESS.getCode(), msg, data);
    }

    /**
     * 失败响应（使用枚举自带的码与文案）。
     *
     * @param resultCode 响应码枚举
     * @param <T>        数据类型
     * @return {@code data = null} 的失败响应体
     */
    public static <T> Result<T> failure(ResultCode resultCode) {
        return failure(resultCode.getCode(), resultCode.getMsg());
    }

    /**
     * 失败响应（自定义码与文案）：供"枚举码 + 上下文细节"的组合场景使用，
     * 例如健康检查把具体是哪个依赖不可用拼进 msg。
     *
     * @param code 响应码（非 0）
     * @param msg  可展示给前端的提示信息
     * @param <T>  数据类型
     * @return {@code data = null} 的失败响应体
     */
    public static <T> Result<T> failure(int code, String msg) {
        return new Result<>(code, msg, null);
    }

    /**
     * 失败响应（携带业务数据）。
     *
     * <p>用于"请求失败但失败详情本身有展示价值"的场景：典型是健康检查 ——
     * 依赖不可用时返回 HTTP 503，但 {@code data} 仍带上完整报告，
     * 前端据 {@code msg} 弹提示的同时，还能从 {@code data.checks} 定位是哪个依赖挂了。
     *
     * @param code 响应码（非 0）
     * @param msg  可展示给前端的提示信息
     * @param data 业务数据，可为 {@code null}
     * @param <T>  数据类型
     * @return 失败响应体
     */
    public static <T> Result<T> failure(int code, String msg, T data) {
        return new Result<>(code, msg, data);
    }

    public int getCode() {
        return code;
    }

    public void setCode(int code) {
        this.code = code;
    }

    public String getMsg() {
        return msg;
    }

    public void setMsg(String msg) {
        this.msg = msg;
    }

    public T getData() {
        return data;
    }

    public void setData(T data) {
        this.data = data;
    }

    @Override
    public String toString() {
        return "Result{code=" + code + ", msg='" + msg + "', data=" + data + '}';
    }
}
