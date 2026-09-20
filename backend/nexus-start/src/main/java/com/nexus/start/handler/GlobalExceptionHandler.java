package com.nexus.start.handler;

import com.nexus.common.exception.BusinessException;
import com.nexus.common.exception.SystemException;
import com.nexus.common.exception.UnauthorizedException;
import com.nexus.common.result.Result;
import com.nexus.common.result.ResultCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.List;

/**
 * 全局异常处理：保证任何异常出口都返回统一响应体 {@link Result}，且不泄露内部信息。
 *
 * <p>处理矩阵（与 CLAUDE.md 宪法约束一致）：
 * <table border="1">
 *     <caption>异常分类与出口</caption>
 *     <tr><th>异常类型</th><th>HTTP</th><th>code</th><th>日志级别</th><th>msg 是否可展示</th></tr>
 *     <tr><td>{@link BusinessException}</td><td>200</td><td>业务码（非 0）</td><td>warn</td><td>是</td></tr>
 *     <tr><td>{@link UnauthorizedException}</td><td>401</td><td>40100 / 40101 / 40102</td><td>warn</td><td>是</td></tr>
 *     <tr><td>{@link MethodArgumentNotValidException}</td><td>200</td><td>40001</td><td>warn（打字段级明细，不打堆栈）</td><td>是</td></tr>
 *     <tr><td>{@link HttpMessageNotReadableException}</td><td>200</td><td>40001</td><td>warn</td><td>是</td></tr>
 *     <tr><td>{@link TaskRejectedException}</td><td>503</td><td>20100</td><td>warn（异常 message 自带线程池现场）</td><td>是</td></tr>
 *     <tr><td>{@link SystemException}</td><td>500</td><td>50000</td><td>error（含堆栈）</td><td>否，只回通用话术</td></tr>
 *     <tr><td>{@link NoResourceFoundException}</td><td>404</td><td>40400</td><td>warn</td><td>是</td></tr>
 *     <tr><td>其他 {@link Exception}</td><td>500</td><td>50000</td><td>error（含堆栈）</td><td>否，只回通用话术</td></tr>
 * </table>
 *
 * <p>业务异常为何用 HTTP 200：本项目以"统一响应体 + 业务码"作为前端的判定依据
 * （前端拦截器按 {@code code === 0} 判成功），HTTP 状态码留给传输/可用性语义
 * （如 /api/health 的 503）。该约定已写入 docs/api/README.md，前后端一致。
 *
 * <p>未认证异常为何是 HTTP 401（阶段1 新增）：认证失败是<b>传输层</b>语义 ——
 * 前端 {@code request.ts} 的 401 分支负责"清 token + 跳登录"，契约在先。
 * 注意本出口只覆盖<b>进入 MVC 之后</b>抛出的未认证异常；
 * 过滤器（DispatcherServlet 之前）的 401 由 {@code JwtAuthenticationFilter} 自己序列化，
 * 两处出口的响应体结构一致，均为 {@code Result}。
 *
 * <p>校验失败为何是 200 + 40001 而非 400（阶段2 新增）：与业务异常同一条理由 ——
 * {@code docs/api/README.md} §1.2 已把「参数不合法」归入"业务失败 → HTTP 200"，
 * 且 openapi.yaml 头部只列举了 401/404/500/503。选 400 就必须在同一次改动里改这两份
 * 已发布契约（见 {@link ResultCode#PARAM_INVALID}）。
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
     * 未认证异常：登录态缺失或失效（HTTP 401）。
     *
     * <p>与 {@link BusinessException} 的处理风格一致 —— warn 级别、不打堆栈：
     * "没带 token / token 过期"是<b>预期内</b>的请求结果，不是程序缺陷。
     * HTTP 401 与响应体里的 40100/40101/40102 同时给出：
     * 前者供 axios 走 401 分支（清 token + 跳登录），后者供提示文案。
     *
     * @param ex 未认证异常
     * @return HTTP 401 + 对应响应码
     */
    @ExceptionHandler(UnauthorizedException.class)
    public ResponseEntity<Result<Void>> handleUnauthorizedException(UnauthorizedException ex) {
        log.warn("未认证：code={} msg={}", ex.getCode(), ex.getMessage());
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(Result.failure(ex.getCode(), ex.getMessage()));
    }

    /**
     * 请求体校验失败：{@code @Valid} 拦下的 Bean Validation 违规（阶段2 新增）。
     *
     * <p>出口是 <b>HTTP 200 + 40001</b>，理由是"参数不合法"属业务失败（见类注释）。
     * 返回的 {@code msg} 用枚举自带的通用文案，<b>不</b>把字段级明细回给前端 ——
     * 明细只进日志（warn 级、不打堆栈），既够定位问题，又不引入"把校验器实现细节当契约"的耦合。
     *
     * <p>本出口存在的<b>前提</b>是运行期真有校验实现：Boot 3 的 starter-web 不带
     * hibernate-validator，缺了它 {@code @Valid} 会静默失效，本方法成死代码。
     * 故 nexus-module-ai 显式声明了 {@code spring-boot-starter-validation}（见其 pom 注释）。
     *
     * @param ex 参数校验异常
     * @return HTTP 200 + 40001
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Result<Void>> handleMethodArgumentNotValid(MethodArgumentNotValidException ex) {
        // 刻意只记"哪个字段违反了哪条约束"，不记字段值：校验失败的入参可能含用户正文
        // （阶段2 的对话消息就是这么用的），记进日志等于把隐私与日志体积问题一起引进来。
        log.warn("参数校验失败：{}", describeFieldErrors(ex.getFieldErrors()));
        return ResponseEntity.ok(Result.failure(ResultCode.PARAM_INVALID));
    }

    /**
     * 请求体不可读：JSON 畸形、类型不匹配等（阶段2 新增）。
     *
     * <p>与 {@link #handleMethodArgumentNotValid} 同归 40001 + HTTP 200 —— 对前端而言
     * "JSON 都没解析出来"与"解析出来了但没通过校验"是同一种处置（改请求重发）。
     *
     * <p>它同时是"契约要 10200、而框架先抛异常"那条的兜底：若把请求体里的
     * {@code modelType} 直接声明成枚举类型，未知取值会走到这里（500 + 50000 的旧行为）——
     * 现在的正解是 DTO 用 {@code String} 承接、由业务层显式 parse（见 ChatRequest）。
     *
     * @param ex 请求体不可读异常
     * @return HTTP 200 + 40001
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Result<Void>> handleHttpMessageNotReadable(HttpMessageNotReadableException ex) {
        log.warn("请求体不可读（JSON 畸形或类型不匹配）：{}", ex.getMessage());
        return ResponseEntity.ok(Result.failure(ResultCode.PARAM_INVALID));
    }

    /**
     * 模型线程池已满（阶段2 新增）：唯一一个走 <b>HTTP 503</b> 的业务侧可用性出口。
     *
     * <p>为什么单开一条而不是落兜底：兜底是 500 + 50000，会把"本服务此刻并发满了、稍后重试即可"
     * 报成"系统故障"。契约（{@code docs/api/README.md} §6.3）把这一格明确写成 <b>503 + 20100</b>，
     * 与 {@code /api/health} 的 503 同一条思路：<b>HTTP 状态码承担可用性语义，业务码承担具体原因</b>。
     * 这也是"全异步"口径下唯一还能给 503 的失败点 —— 池满是开流前同步可判的，
     * 而上游不可达只在工作线程上才暴露，那时响应头已经发出去了（只能写 {@code error} 帧）。
     *
     * <p>warn 级、不打堆栈：它不是缺陷，是容量信号。异常的 message 自带线程池现场
     * （{@code pool size / active threads / queued tasks}），排查"为什么池满了"看它就够。
     *
     * @param ex 线程池拒绝执行异常
     * @return HTTP 503 + 20100
     */
    @ExceptionHandler(TaskRejectedException.class)
    public ResponseEntity<Result<Void>> handleTaskRejectedException(TaskRejectedException ex) {
        log.warn("模型线程池已满，拒绝本次对话：{}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Result.failure(ResultCode.CHAT_UPSTREAM_UNAVAILABLE));
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

    /**
     * 把字段级校验失败压成一行日志文本，形如 {@code messages size must be between 1 and ...; modelType ...}。
     *
     * <p>用 {@link StringBuilder} 而非 {@code stream().collect(joining())}：本方法在异常路径上被调用，
     * 不值得为一行日志引入流式写法（且异常对象可能很大，多一次装箱没有必要）。
     *
     * @param fieldErrors 字段错误列表
     * @return 单行描述；无字段错误时返回 {@code "(无字段级明细)"}
     */
    private static String describeFieldErrors(List<FieldError> fieldErrors) {
        if (fieldErrors.isEmpty()) {
            return "(无字段级明细)";
        }
        StringBuilder builder = new StringBuilder();
        for (FieldError fieldError : fieldErrors) {
            if (builder.length() > 0) {
                builder.append("; ");
            }
            builder.append(fieldError.getField()).append(' ').append(fieldError.getDefaultMessage());
        }
        return builder.toString();
    }
}
