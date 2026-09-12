package com.nexus.common.exception;

import java.io.Serial;

/**
 * 系统异常：非预期的、由系统自身或外部依赖故障引发的错误。
 *
 * <p>处理约定（见 {@code GlobalExceptionHandler}）：
 * 服务端以 error 级别记录完整堆栈，返回给前端的只有通用话术
 * （{@link com.nexus.common.result.ResultCode#SYSTEM_ERROR}），
 * <b>严禁把 {@code getMessage()} 或堆栈写进响应体</b> —— 那会把表名、
 * SQL 片段、内网地址等内部信息泄露给前端。
 *
 * <p>典型场景：外部依赖不可用（Redis / Ollama 调用失败）、数据一致性校验不通过、
 * 兜底分支不可达等"程序员写错了"或"环境坏了"的情况。
 *
 * @author nexus
 */
public class SystemException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 构造系统异常。
     *
     * @param msg 仅用于服务端日志的描述信息，不会返回给前端
     */
    public SystemException(String msg) {
        super(msg);
    }

    /**
     * 构造系统异常（保留原始异常链，便于日志定位根因）。
     *
     * @param msg   仅用于服务端日志的描述信息
     * @param cause 原始异常
     */
    public SystemException(String msg, Throwable cause) {
        super(msg, cause);
    }
}
