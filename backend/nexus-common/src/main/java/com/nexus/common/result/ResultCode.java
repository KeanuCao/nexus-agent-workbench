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
