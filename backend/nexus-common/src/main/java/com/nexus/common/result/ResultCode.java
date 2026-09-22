package com.nexus.common.result;

/**
 * 统一响应码。
 *
 * <p>契约（docs/design/00-环境与部署.md §5.3）：{@code code = 0} 表示成功，非 0 表示失败。
 *
 * <p>编码分段约定（阶段1 在此规则下细分业务码，**不复用**已有值）：
 * <ul>
 *     <li>0：成功</li>
 *     <li>1xxxx：通用业务异常（可展示给前端，HTTP 200 出口）</li>
 *     <li>2xxxx：依赖/服务可用性异常（如健康检查探活失败）</li>
 *     <li>4xxxx：请求侧问题（资源不存在、未认证/登录态失效、参数校验不通过）</li>
 *     <li>5xxxx：系统异常（不对外暴露细节，仅记日志）</li>
 * </ul>
 *
 * <p>为什么 40100/40101/40102 不与 404（40400）合并成一个码：三者的前端处置与排查方向不同 ——
 * 「没带 token」是客户端缺陷、「登录已过期」重新登录即可、「登录态无效」则是伪造/被登出的异常信号。
 * 契约见 docs/design/01-多租户与认证.md §5.4。
 *
 * @author nexus
 */
public enum ResultCode {

    /** 成功。msg 固定为 {@code success}，与设计文档 §5.3 契约逐字对齐。 */
    SUCCESS(0, "success"),

    /** 通用业务失败：由 {@link com.nexus.common.exception.BusinessException} 默认使用。 */
    BUSINESS_ERROR(10000, "业务处理失败"),

    /**
     * 登录失败（用户名或密码错误）。
     *
     * <p><b>刻意不区分</b>"用户名不存在"与"密码错误" —— 区分会给出账号枚举的探测口
     * （攻击者可据此判断哪些用户名存在）。前端不得依据本文案做分支判断。
     */
    LOGIN_FAILED(10100, "用户名或密码错误"),

    /** 账号已停用（{@code t_user.status = 0}）：密码校验通过后才判定，避免泄露"该账号存在"。 */
    ACCOUNT_DISABLED(10101, "账号已停用，请联系管理员"),

    /** 租户已停用（{@code t_tenant.status = 0}）：账号正常但所属租户被停用，一律拒绝登录。 */
    TENANT_DISABLED(10102, "租户已停用，请联系管理员"),

    /**
     * 不支持的模型类型（阶段2 对话接口）：
     * {@code POST /api/chat/stream} 请求体里的 {@code modelType} 取值不在 {@code ModelType} 枚举内。
     *
     * <p>归 1xxxx 而非 4xxxx：task.3 的验收标准 2.2 点名「未知类型抛<b>业务异常</b>」，
     * 它由 {@link com.nexus.common.exception.BusinessException} 抛出（HTTP 200 出口）。
     */
    CHAT_MODEL_UNSUPPORTED(10200, "不支持的模型类型"),

    /**
     * 不支持的文件类型（阶段3 知识库）：扩展名不在白名单（{@code TikaDocumentParser} 里的常量
     * {@code Set.of("txt", "pdf")} —— <b>刻意不做成配置键</b>，理由见下方），
     * 或扩展名与内容检测不符（{@code .pdf} 的扩展名但内容检测为纯文本之类）。
     *
     * <p>归 1xxxx 而非 4xxxx：与 {@link #CHAT_MODEL_UNSUPPORTED} 同一条取舍 —— 它由
     * {@link com.nexus.common.exception.BusinessException} 抛出（HTTP 200 出口，文案可直接展示），
     * 且 4xxxx 那一段在本项目里已被"参数不合法 / 媒体类型 / 文件过大"占满，再塞一条会把
     * "客户端传参问题"与"上传内容的业务规则"混成一类。契约见 {@code docs/api/README.md} §7.8。
     *
     * <p><b>为什么白名单是常量而不是配置键</b>（初版设计曾写 {@code nexus.ai.rag.allowed-extensions}，
     * 实施时改掉）：白名单一旦可配，本码写死的文案"仅支持 TXT / PDF"就会<b>说谎</b> —— 除非把文案也
     * 做成运行期可变的，而那会把"接口文案"变成动态值。而"支持哪几种格式"本来就是<b>范围决策</b>
     * （加 Word 要同时加 Tika 模块与用例，设计 §10.1），不是运维旋钮。常量 + 写死文案 ⇒ 两处同源，
     * 改一次全对。
     */
    KB_FILE_TYPE_UNSUPPORTED(10201, "不支持的文件类型，仅支持 TXT / PDF"),

    /**
     * 文档不存在或已被删除（阶段3 知识库）：删除接口的目标 id 查不到，或它不属于当前租户。
     *
     * <p><b>两个刻意的约定</b>（契约 {@code docs/api/README.md} §7.3）：
     * <ol>
     *     <li>用 HTTP <b>200</b> + 本码，而不是 404 —— 本项目的 HTTP 状态码只承载传输/可用性语义
     *         （契约 §1.2），业务拒绝一律 200 + 业务码；且 404 出口需要新造一套异常类型，
     *         收益不抵成本；</li>
     *     <li>"别的租户的文档"与"不存在"在本接口里<b>完全同形</b>（都返回本码）：区分开就等于
     *         给出"某 id 是否存在"的探测口，跨租户删除尝试因此不返回 403 之类的额外信号。</li>
     * </ol>
     */
    KB_DOCUMENT_NOT_FOUND(10202, "文档不存在或已被删除"),

    /**
     * 未能解析出文本（阶段3 知识库）：扫描版 PDF（有页面、没有文本层）、空文件、
     * 编码不可识别（UTF-8 与 GBK 解出来都超标），以及 Tika 侧的加密/损坏文件
     * （{@code TikaException} / {@code SAXException} 统一归这一档，不把上游异常原样抛给用户）。
     *
     * <p><b>为什么单开一个码而不复用 10000</b>：这四种原因的用户动作相同（换一份文件），
     * 但它是"上传看似成功、内容却是空的"这类<b>静默失败</b>的唯一出口 —— 没有它，扫描版 PDF
     * 会变成"上传成功但什么都检索不到"，把排查方向带向检索侧（契约 §7.7 正是按这个思路排的）。
     */
    KB_PARSE_EMPTY(10203, "未能从文件中解析出文本（可能是扫描版 PDF 或空文件）"),

    /**
     * 文档内容过长（阶段3 知识库）：分块数超过 {@code nexus.ai.rag.max-chunks-per-document}（默认 3000）。
     *
     * <p><b>判在向量化之前</b>（决策 D15）：10MB 的 TXT ≈ 2 万块 ≈ 上万次 embedding 调用，
     * "先分块、数一眼、再决定调不调 embedding"让失败在几秒内以本码发生，而不是几分钟后
     * 以一个上游超时（{@link #CHAT_UPSTREAM_UNAVAILABLE}）的形态出现 —— 后者的排查方向是错的。
     *
     * <p>与 {@link #FILE_TOO_LARGE} 的分工：那个管<b>字节数</b>（multipart 层），本码管<b>分块数</b>
     * （业务层）。同样 10MB，纯文本与 PDF 解析出的字符数能差一个数量级，故两道闸都要有。
     */
    KB_CONTENT_TOO_LARGE(10204, "文档内容过长，超出单文档分块上限，请拆分后上传"),

    /** 请求的资源/路径不存在：404 出口使用（见 GlobalExceptionHandler）。 */
    NOT_FOUND(40400, "请求的资源不存在"),

    /** 未携带 token：受保护接口在无 {@code Authorization: Bearer} 时使用（HTTP 401）。 */
    UNAUTHENTICATED(40100, "未登录，请先登录"),

    /**
     * 登录状态无效：token 伪造/签名不匹配/格式非法，或 Redis 白名单中已无该 {@code jti}
     * （已在别处登出、Redis 被清）—— 都是"这个凭据不该被接受"（HTTP 401）。
     */
    TOKEN_INVALID(40101, "登录状态无效，请重新登录"),

    /** 登录已过期：token 的 {@code exp} 已过（HTTP 401）。与"无效"分开，前端可提示"重新登录即可"。 */
    TOKEN_EXPIRED(40102, "登录已过期，请重新登录"),

    /**
     * 请求参数不合法（阶段2）：Bean Validation 校验失败（如 {@code messages} 为空）
     * 或请求体 JSON 畸形 —— 由 GlobalExceptionHandler 的
     * {@code MethodArgumentNotValidException} / {@code HttpMessageNotReadableException} 两个出口使用。
     *
     * <p><b>配套 HTTP 200 而非 400</b>：{@code docs/api/README.md} §1.2 已把「参数不合法」明确归入
     * "业务失败 → HTTP 200"（业务失败不污染前端的 axios 失败分支），选 400 就必须在同一次改动里
     * 改掉 README 与 openapi.yaml 两份已发布契约 —— 收益不抵成本。
     */
    PARAM_INVALID(40001, "请求参数不合法"),

    /**
     * 请求的媒体类型不受支持（阶段2）：缺 {@code Content-Type} 或不是 {@code application/json}
     * —— 由 GlobalExceptionHandler 的 {@code HttpMediaTypeNotSupportedException} 出口使用。
     *
     * <p><b>配套 HTTP 415 而非 200 / 500</b>：这属于传输层语义（请求的媒体类型不被接受），
     * 与 401/404 同类；而"缺了它就被兜底成 500 + 50000『系统繁忙』"是实测出来的缺陷 ——
     * 把调用方的请求格式问题报成服务端故障，`docs/api/README.md` §6.1 承诺的
     * "看到 415 就知道是 Content-Type 没带"那条诊断链路会整个失效。2026-09-20 修复。
     */
    UNSUPPORTED_MEDIA_TYPE(40002, "请求格式不支持，请使用 application/json"),

    /**
     * 上传文件过大（阶段3）：超过 {@code spring.servlet.multipart.max-file-size}（本项目配 10MB）。
     * 由 GlobalExceptionHandler 的 {@code MaxUploadSizeExceededException} 出口使用。
     *
     * <p><b>为什么列在通用段（{@code docs/api/README.md} §1.3）而不是知识库那一段</b>：
     * 它约束的是 multipart 请求体本身，任何上传接口都会撞上，与"知识库"这个业务域无关。
     * 按业务域归码的代价是：下一个上传接口要么复用知识库的码（读起来像串了模块），
     * 要么再造就一个同含义的码（同一件事两个码）。
     *
     * <p>配套 HTTP <b>200</b>：与 {@link #PARAM_INVALID} 同一条理由（业务失败不污染前端的
     * axios 失败分支），见其 javadoc。
     *
     * <p>⚠️ <b>待实测</b>：Tomcat 的 {@code max-swallow-size}（默认约 2MB）可能让超限请求表现为
     * "连接被重置"而不是本码 —— 设计 §6.2 与契约 §7.7 已记该风险与判据（故意上传一个 11MB 的文件）。
     * <p>⚠️ msg 里的 "10MB" 与 yml 的 {@code max-file-size} 是两处，改一处忘另一处文案就会失真
     * （与 {@link #KB_FILE_TYPE_UNSUPPORTED} 同类的取舍，记在明处）。
     */
    FILE_TOO_LARGE(40003, "文件过大，最大支持 10MB"),

    /** 依赖服务不可用：健康检查探活失败（HTTP 503）时使用。 */
    SERVICE_UNAVAILABLE(20000, "依赖服务不可用"),

    /**
     * 模型服务暂时不可用（阶段2 对话接口）。<b>有两种载体，这是有意的</b>：
     * <ul>
     *     <li>HTTP <b>503</b> + 普通 {@code Result}：模型专用线程池已满 —— 本服务侧、开流前同步可判，
     *         能给出真正的 503（见 AiModelService 与决策 D8）；</li>
     *     <li>HTTP <b>200</b> + {@code event: error} 帧：上游模型不可达 / 超时 / 中途报错 ——
     *         只在工作线程上才暴露，那时 {@code text/event-stream} 的响应头已经发出去了。</li>
     * </ul>
     * 前端两种都要处理（先看 {@code Content-Type} 再按帧解析）；排查方向由 HTTP 状态码区分开
     * （503 且有 {@code Result} = 本服务；200 + error 帧 = 上游），故不再加第三个码。
     */
    CHAT_UPSTREAM_UNAVAILABLE(20100, "模型服务暂时不可用，请稍后重试"),

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
