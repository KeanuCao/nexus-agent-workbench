package com.nexus.start.handler;

import com.nexus.common.exception.BusinessException;
import com.nexus.common.exception.SystemException;
import com.nexus.common.result.Result;
import com.nexus.common.result.ResultCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 全局异常处理：保证任何异常出口都返回统一响应体 {@link Result}，且不泄露内部信息。
 *
 * <p>处理矩阵（与 CLAUDE.md 宪法约束一致）：
 * <table border="1">
 *     <caption>异常分类与出口</caption>
 *     <tr><th>异常类型</th><th>HTTP</th><th>code</th><th>日志级别</th><th>msg 是否可展示</th></tr>
 *     <tr><td>{@link BusinessException}</td><td>200</td><td>业务码（非 0）</td><td>warn</td><td>是</td></tr>
 *     <tr><td>{@link SystemException}</td><td>500</td><td>50000</td><td>error（含堆栈）</td><td>否，只回通用话术</td></tr>
 *     <tr><td>{@link NoResourceFoundException}</td><td>404</td><td>40400</td><td>warn</td><td>是</td></tr>
 *     <tr><td>其他 {@link Exception}</td><td>500</td><td>50000</td><td>error（含堆栈）</td><td>否，只回通用话术</td></tr>
 * </table>
 *
 * <p>业务异常为何用 HTTP 200：本项目以"统一响应体 + 业务码"作为前端的判定依据
 * （前端拦截器按 {@code code === 0} 判成功），HTTP 状态码留给传输/可用性语义
 * （如 /api/health 的 503）。该约定已写入 docs/api/README.md，前后端一致。
 *
 * @author nexus
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * 业务异常：可预期、可展示。
     *
     * @param ex 业务异常
     * @return HTTP 200 + 非 0 业务码
     */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Result<Void>> handleBusinessException(BusinessException ex) {
        // 业务异常不打堆栈：它是"规则没通过"，不是程序缺陷
        log.warn("业务异常：code={} msg={}", ex.getCode(), ex.getMessage());
        return ResponseEntity.ok(Result.failure(ex.getCode(), ex.getMessage()));
    }

    /**
     * 系统异常：非预期，只记日志。
     *
     * @param ex 系统异常
     * @return HTTP 500 + 通用话术
     */
    @ExceptionHandler(SystemException.class)
    public ResponseEntity<Result<Void>> handleSystemException(SystemException ex) {
        log.error("系统异常：msg={}", ex.getMessage(), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Result.failure(ResultCode.SYSTEM_ERROR));
    }

    /**
     * 路径不存在：返回 404 而非被兜底处理器吞成 500（Spring 6.1 的静态资源未命中异常）。
     *
     * @param ex 资源未找到异常
     * @return HTTP 404 + 统一响应体
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Result<Void>> handleNoResourceFoundException(NoResourceFoundException ex) {
        log.warn("路径不存在：path={}", ex.getResourcePath());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Result.failure(ResultCode.NOT_FOUND));
    }

    /**
     * 兜底：未预期异常统一收敛。
     *
     * <p>此处是"必须 catch 宽泛异常"的正当场景 —— 若让异常穿透到容器默认错误页，
     * 前端拿到的就不再是 {@code Result} 结构；同时响应体只回通用话术，
     * 堆栈仅进服务端日志（严禁返回给前端）。
     *
     * @param ex 未预期异常
     * @return HTTP 500 + 通用话术
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Void>> handleException(Exception ex) {
        log.error("未预期异常：type={}", ex.getClass().getName(), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Result.failure(ResultCode.SYSTEM_ERROR));
    }
}
