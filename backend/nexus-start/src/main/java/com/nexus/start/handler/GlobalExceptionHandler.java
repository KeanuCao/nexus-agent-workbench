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
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
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
 *     <tr><td>{@link BusinessException} 且 {@code code=20100}</td><td><b>503</b></td>
 *         <td>{@code CHAT_UPSTREAM_UNAVAILABLE}</td><td>warn</td><td>是</td></tr>
 *     <tr><td>{@link UnauthorizedException}</td><td>401</td><td>40100 / 40101 / 40102</td><td>warn</td><td>是</td></tr>
 *     <tr><td>{@link MethodArgumentNotValidException}</td><td>200</td><td>40001</td><td>warn（打字段级明细，不打堆栈）</td><td>是</td></tr>
 *     <tr><td>{@link HttpMessageNotReadableException}</td><td>200</td><td>40001</td><td>warn</td><td>是</td></tr>
 *     <tr><td>{@link HttpMediaTypeNotSupportedException}（目标接口 <b>consumes JSON</b>）</td><td><b>415</b></td>
 *         <td>40002</td><td>warn</td><td>是</td></tr>
 *     <tr><td>{@link HttpMediaTypeNotSupportedException}（目标接口 <b>consumes multipart</b>，2026-09-22 补）</td>
 *         <td>200</td><td>40001</td><td>warn</td><td>是</td></tr>
 *     <tr><td>{@link MaxUploadSizeExceededException}</td><td>200</td><td>40003</td><td>warn</td><td>是</td></tr>
 *     <tr><td>{@link MultipartException}</td><td>200</td><td>40001</td><td>warn</td><td>是</td></tr>
 *     <tr><td>{@link MissingServletRequestPartException}</td><td>200</td><td>40001</td><td>warn</td><td>是</td></tr>
 *     <tr><td>{@link MissingServletRequestParameterException}</td><td>200</td><td>40001</td><td>warn</td><td>是</td></tr>
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
 * <p><b>唯一的例外是 {@code code=20100}</b>（阶段3 知识库链路）：契约 §7.7 把
 * "向量化 / 生成时上游不可达"写死为 <b>503 + 20100</b>，且那条链路全程同步 ——
 * 20100 只有一种载体，不像对话接口还能走 {@code event: error} 帧。
 * 例外只在这一处、且由码判定（见 {@link #handleBusinessException} 的注释）。
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
 * <p>阶段3 的四个"请求形状"出口（{@code MaxUploadSizeExceeded} / {@code Multipart} /
 * {@code MissingServletRequestPart} / {@code MissingServletRequestParameter}）是<b>同一类问题的
 * 事前处置</b>：阶段2 的 {@code HttpMediaTypeNotSupportedException} 曾经落进兜底、把客户端的请求
 * 拼写问题报成 500「系统繁忙」，把排查方向整个带偏（{@code docs/api/README.md} §1.2 有完整记载）。
 * 与其等上传接口上线后再被实测暴露一次，不如把这一族一次性收口。
 *
 * <p>第五格是 2026-09-22 由<b>上线后的实测</b>补的：上传接口"带错 / 缺 {@code Content-Type}"这一格
 * <b>不在上面那四个出口里</b> —— 它由映射阶段的 {@code consumes} 条件抛
 * {@code HttpMediaTypeNotSupportedException}（不是 {@code MultipartException}），若沿用阶段2 那条
 * 415 出口，用户会读到"请使用 application/json"这条<b>反向误导</b>的文案（他真正该做的是让浏览器
 * 带上 boundary）。故 {@link #handleHttpMediaTypeNotSupported} 里按"该接口消费什么媒体类型"分流。
 *
 * @author nexus
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * 业务异常：可预期、可展示。
     *
     * <p><b>默认出口 HTTP 200 + 非 0 业务码</b>，但有<b>一个按码分流的特例</b>：
     * {@link ResultCode#CHAT_UPSTREAM_UNAVAILABLE}（20100）走 <b>HTTP 503</b> ——
     * 见方法内的注释（依据是契约 {@code docs/api/README.md} §7.7）。
     *
     * @param ex 业务异常
     * @return HTTP 200 + 非 0 业务码；{@code code=20100} 时为 HTTP 503
     */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Result<Void>> handleBusinessException(BusinessException ex) {
        // 业务异常不打堆栈：它是"规则没通过"，不是程序缺陷
        log.warn("业务异常：code={} msg={}", ex.getCode(), ex.getMessage());
        if (ex.getCode() == ResultCode.CHAT_UPSTREAM_UNAVAILABLE.getCode()) {
            // ★ 有意为之的**按码分流**（依据：契约 docs/api/README.md §7.7 与设计 §3.7）：
            //   知识库链路（阶段3）全程同步，20100 的载体**只有 503 一种**
            //   （不像对话接口那样还有 event: error 帧）—— 契约把"向量化 / 生成时上游不可达"
            //   写死为 HTTP 503 + 20100，故这里不能沿用"业务异常一律 200"的默认出口。
            //   本类其余出口一概不动。
            //
            // 为什么不新建一个异常类型：那要改所有抛出点（含阶段2 的两个 provider），
            //   而"码即语义"在本项目已经成立（ResultCode 就是码的唯一真源），四行分流代价最小。
            //
            // 为什么不影响阶段2（已逐点复核，2026-09-22）：
            //   ① 池满 → chatExecutor.execute(...) 在**请求线程**上抛 TaskRejectedException，
            //      由本类下方那个出口给 503 + 20100，不经过这里；
            //   ② 上游不可达 / 超时 / 中途报错 → 发生在 ChatServiceImpl 的**工作线程**里，
            //      被原地 catch 成 error 帧（响应头早已发出，异常根本没有出口）。
            //   ⇒ 能走到本分支的生产路径只有知识库链路（OllamaEmbeddingService 的 3 处、
            //     KbAskServiceImpl 的 1 处，以及 ModelAnswerGenerator 对 provider 20100 的透传）。
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Result.failure(ex.getCode(), ex.getMessage()));
        }
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
     * 请求的媒体类型不受支持（阶段2 新增；2026-09-22 起按目标接口的 {@code consumes} 分两格）。
     *
     * <p><b>为什么必须单开一条（这是实测出来的缺陷）</b>：没有它时，{@code HttpMediaTypeNotSupportedException}
     * 会落进 {@link #handleException} 兜底 → 客户端<b>忘带头</b>被报成 <b>500 + 50000「系统繁忙」</b>。
     * 那一格把排查方向整个带偏：明明是调用方的请求格式问题，却显示成服务端故障 ——
     * 而契约（{@code docs/api/README.md} §6.1 与设计 §5.1-2）恰恰把 415 写成前端诊断
     * "是不是 Content-Type 没带"的依据。2026-09-20 由 TC-02 的只读探针实测暴露，同日修复。
     *
     * <p>用 <b>415</b> 而不是又一个 200：这属于传输层语义（请求的媒体类型不被接受），
     * 与 401/404 同类；{@link ResultCode#PARAM_INVALID} 那条配套 200 的理由（业务失败不污染
     * 前端的失败分支）在这里不成立 —— 请求根本没被解析成业务入参。
     *
     * <p><b>2026-09-22 分流（第二格）：目标接口消费 multipart 时走 200 + 40001</b>。上传接口
     * {@code POST /api/kb/documents}（{@code consumes = multipart/form-data}）收到非 multipart 请求时，
     * {@code @RequestMapping} 的 {@code consumes} 条件在<b>映射阶段</b>就把请求挡下并抛本异常 ——
     * 它到不了 {@code MultipartException}、也到不了参数绑定，所以上面那四个"请求形状"出口一个都不命中。
     * 若沿用 415 + {@link ResultCode#UNSUPPORTED_MEDIA_TYPE}，返回的文案"请使用 <b>application/json</b>"
     * 是<b>反向误导</b>（用户会去改 JSON 头，而正确动作是让浏览器带上 boundary）；契约把这一格写死为
     * <b>200 + 40001</b>（{@code docs/api/README.md} §7.7 第 2 行与 §7.8 的备注；机器可读版
     * {@code docs/api/openapi.yaml} 的上传 operation <b>只声明 200/401/503</b>，没有 415）。
     *
     * @param ex 媒体类型不支持异常
     * @return 目标接口消费 multipart 时 200 + 40001；否则 415 + 40002
     */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<Result<Void>> handleHttpMediaTypeNotSupported(HttpMediaTypeNotSupportedException ex) {
        if (isMultipartEndpoint(ex)) {
            // 只记"收到的是什么"，不记 content-type 明细：这条日志的价值在于定位"哪个调用方的头没带对"
            log.warn("multipart 接口收到非 multipart 请求（缺 Content-Type 或带错了头）：contentType={}",
                    ex.getContentType());
            return ResponseEntity.ok(Result.failure(ResultCode.PARAM_INVALID));
        }
        log.warn("请求媒体类型不受支持：contentType={}", ex.getContentType());
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
                .body(Result.failure(ResultCode.UNSUPPORTED_MEDIA_TYPE));
    }

    /**
     * 判断抛出 415 的那个映射是否在消费 {@code multipart/form-data}（决定 415 出口走哪一格）。
     *
     * <p><b>判据取自异常自带的 {@code supportedMediaTypes}</b>，不依赖 URL 白名单：spring-webmvc 6.1 的
     * {@code RequestMappingInfoHandlerMapping.handleNoMatch} 是用
     * {@code new ArrayList<>(PartialMatchHelper.getConsumableMediaTypes())} 构造这个列表的
     * （即"部分匹配的那个映射的 {@code consumes} 值"）。
     *
     * <p>为什么这条判据不会误伤 JSON 接口：{@code @RequestBody} 解析阶段抛出的同类异常，其列表来自
     * {@code HttpMessageConverter} 的支持类型，<b>不可能</b>含 {@code multipart/form-data}。
     *
     * @param ex 媒体类型不支持异常
     * @return 目标接口消费 multipart 时为 {@code true}
     */
    private static boolean isMultipartEndpoint(HttpMediaTypeNotSupportedException ex) {
        for (MediaType supported : ex.getSupportedMediaTypes()) {
            if (supported.isCompatibleWith(MediaType.MULTIPART_FORM_DATA)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 上传文件超过大小上限（阶段3 新增，随知识库的上传接口一起落地）。
     *
     * <p><b>与下面 {@link #handleMultipartException} 的匹配关系</b>：本异常是
     * {@code MultipartException} 的<b>子类</b>。Spring 的 {@code ExceptionHandlerMethodResolver}
     * 是按异常类型的继承距离挑出口的（选最贴近的那个），所以声明顺序技术上不决定匹配结果 ——
     * 但这里仍把子类排在父类之前：读代码的人顺着扫下来，才不会误以为"文件超限会落进父类那格"、
     * 被报成一个误导性的 40001。
     *
     * <p>出口 <b>200 + 40003</b>：与 {@link ResultCode#PARAM_INVALID} 同一条理由（业务失败不污染
     * 前端的 axios 失败分支）；而码必须与参数问题分开 —— "文件太大"要用户换文件、"参数不合法"
     * 要用户改请求，两种动作不同，合成一个码就等于让前端只能靠猜。
     *
     * <p>⚠️ <b>待实测</b>：Tomcat 的 {@code max-swallow-size}（默认约 2MB）可能让超限请求
     * 表现为"连接被重置"而根本到不了这里（设计 §6.2 的待实测项，判据见契约 §7.7）。
     *
     * @param ex 上传超限异常
     * @return HTTP 200 + 40003
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Result<Void>> handleMaxUploadSizeExceeded(MaxUploadSizeExceededException ex) {
        // 只记异常自带的消息（含上限值），不记文件名与内容
        log.warn("上传文件超过大小上限：{}", ex.getMessage());
        return ResponseEntity.ok(Result.failure(ResultCode.FILE_TOO_LARGE));
    }

    /**
     * multipart 请求本身不可解析（阶段3 新增）：缺 {@code Content-Type: multipart/form-data}、
     * 缺 boundary、或 body 不是合法的 multipart 结构。
     *
     * <p>出口 <b>200 + 40001</b>，刻意<b>不</b>复用 {@link ResultCode#UNSUPPORTED_MEDIA_TYPE}（415）：
     * 那个码的文案写死是"请使用 <b>application/json</b>"，贴到上传接口上是<b>反向误导</b>
     * ——用户会去改 JSON 头，而真正要做的是让上传请求带上<b>带 boundary 的 {@code multipart/form-data}</b>
     * （实现口径见契约 §7.1 约定 2）。所以 multipart 这一类的失败一律走 40001，靠本条日志说清是哪一种
     * （设计 §3.8 的备注）。
     *
     * <p><b>与 415 出口的分工（2026-09-22 补）</b>：本出口只在"请求确实进了 multipart 解析"时才命中。
     * 若请求<b>压根不是</b> multipart（缺头 / 带错头），Spring 在<b>映射阶段</b>就按 {@code consumes}
     * 条件把它挡下并抛 {@link HttpMediaTypeNotSupportedException} —— 那一路<b>到不了这里</b>，
     * 由 {@link #handleHttpMediaTypeNotSupported} 的分流分支同样回 200 + 40001
     * （详见该方法的注释：为什么不能沿用 415 + 40002）。
     *
     * <p>warn 级、不打堆栈：与其它客户端缺陷出口一致 —— 请求都没拼对，不是服务端故障。
     *
     * @param ex multipart 异常
     * @return HTTP 200 + 40001
     */
    @ExceptionHandler(MultipartException.class)
    public ResponseEntity<Result<Void>> handleMultipartException(MultipartException ex) {
        log.warn("multipart 请求不可解析（缺 Content-Type / 缺 boundary / 结构非法）：{}", ex.getMessage());
        return ResponseEntity.ok(Result.failure(ResultCode.PARAM_INVALID));
    }

    /**
     * 表单里缺少必需的文件 part（阶段3 新增）：例如请求是合法的 multipart，但没有名为
     * {@code file} 的字段（前端把字段名写错成 {@code upload} 之类就是这一格）。
     *
     * <p><b>它不是一个"多余"的出口</b>：本异常<b>不是</b> {@code MultipartException} 的子类
     * （它继承自 {@code ServletRequestBindingException}），不单开就会落进兜底 → 500 + 50000，
     * 把"前端字段名写错"报成服务端故障 —— 与阶段2 那个 415 的缺陷是同一类（见类注释）。
     *
     * @param ex 缺少请求 part 异常
     * @return HTTP 200 + 40001
     */
    @ExceptionHandler(MissingServletRequestPartException.class)
    public ResponseEntity<Result<Void>> handleMissingServletRequestPart(MissingServletRequestPartException ex) {
        log.warn("缺少必需的请求 part（表单字段名可能写错了）：{}", ex.getRequestPartName());
        return ResponseEntity.ok(Result.failure(ResultCode.PARAM_INVALID));
    }

    /**
     * 缺少必需的请求参数（阶段3 新增）：查询串 / 表单里少了声明为必填的参数。
     *
     * <p>它<b>不在</b>本阶段的业务链路上，属"顺手补齐同类缺口"：阶段2 的
     * {@code HttpMediaTypeNotSupportedException} 就是落进兜底被报成 500 之后才补的（见类注释）。
     * 与其等下一次被实测暴露，不如把这一族异常一次性收口 —— 代价只有五行。
     *
     * @param ex 缺少请求参数异常
     * @return HTTP 200 + 40001
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Result<Void>> handleMissingServletRequestParameter(MissingServletRequestParameterException ex) {
        log.warn("缺少必需的请求参数：{}", ex.getParameterName());
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
