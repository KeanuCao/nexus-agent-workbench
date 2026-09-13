package com.nexus.infrastructure.tenant;

/**
 * 当前请求的租户上下文（{@link ThreadLocal} 持有）。
 *
 * <p><b>⚠️ 必须在 {@code finally} 中调用 {@link #clear()}。</b>
 * 线程池会复用线程：一个请求结束时没有清理，下一个请求就会继承上一个请求的租户 ——
 * 表现为「A 租户的登录态查到了 B 租户的数据」，且<strong>不报错、不抛异常</strong>，
 * 是多租户最隐蔽、也最危险的失效形态。因此：
 * <ul>
 *     <li>写入方（{@code JwtAuthenticationFilter}）在 {@code finally} 里清理，
 *         ——即使鉴权失败提前返回，也要走 finally；</li>
 *     <li>{@link ThreadLocal#remove()} 而非赋 null：赋 null 只是覆盖引用，
 *         线程池长期持有该 ThreadLocal 对象本身，属于不必要的滞留。</li>
 * </ul>
 *
 * <p>持有哪些东西（设计依据：docs/design/01-多租户与认证.md §6.1 / §6.3）：
 * <table border="1">
 *     <caption>字段与消费者</caption>
 *     <tr><th>字段</th><th>写入方</th><th>读取方</th></tr>
 *     <tr><td>{@code userId}</td><td>JwtAuthenticationFilter</td><td>AuthService（取当前用户）</td></tr>
 *     <tr><td>{@code tenantId}</td><td>JwtAuthenticationFilter</td><td>TenantLineHandlerImpl（注入 SQL 条件）</td></tr>
 *     <tr><td>{@code tokenId}（JWT 的 {@code jti}）</td>
 *         <td>JwtAuthenticationFilter</td>
 *         <td>AuthService（登出时按 jti 删 Redis 白名单）</td></tr>
 * </table>
 * 其中 {@code tokenId} 是对设计文档 §6.1「ThreadLocal：userId/tenantId + 豁免计数」的一处**增补**：
 * 登出必须按 {@code jti} 删除 Redis 记录，而 {@code jti} 只在过滤器解析 token 时可见 ——
 * 放进上下文，比让 Service 再解析一次请求头（或把 raw token 透传进 Service）都更干净。
 *
 * <p>豁免计数器（{@link #pushIgnore()} / {@link #popIgnore()}）服务于 {@link IgnoreTenant}：
 * 用**计数器而非布尔量**，是因为豁免可能嵌套（外层 Mapper 方法调用内层 Mapper 方法），
 * 布尔量会被内层的弹栈提前清掉，导致外层的豁免失效。
 *
 * @author nexus
 */
public final class TenantContext {

    /** 当前请求的登录态快照（record：不可变，杜绝"取出来再改回去"这种绕过清理的写法）。 */
    private record Snapshot(Long userId, Long tenantId, String tokenId) {
    }

    /** 登录态；未登录（或已清理）时为 {@code null}。 */
    private static final ThreadLocal<Snapshot> SNAPSHOT = new ThreadLocal<>();

    /** 豁免深度：未豁免时为 {@code null}，N 层嵌套豁免时为 N。 */
    private static final ThreadLocal<Integer> IGNORE_DEPTH = new ThreadLocal<>();

    private TenantContext() {
        // 纯静态工具类，不允许实例化
    }

    /**
     * 绑定当前请求的登录态（由 {@code JwtAuthenticationFilter} 在鉴权通过后调用）。
     *
     * @param userId   用户 ID
     * @param tenantId 租户 ID（多租户隔离的依据）
     * @param tokenId  JWT 的 {@code jti}（登出时据此删 Redis 白名单）
     */
    public static void set(Long userId, Long tenantId, String tokenId) {
        SNAPSHOT.set(new Snapshot(userId, tenantId, tokenId));
    }

    /**
     * 当前用户 ID。
     *
     * @return 用户 ID；无登录态时为 {@code null}
     */
    public static Long getUserId() {
        Snapshot snapshot = SNAPSHOT.get();
        return snapshot == null ? null : snapshot.userId();
    }

    /**
     * 当前租户 ID —— 多租户隔离的唯一依据。
     *
     * @return 租户 ID；无租户上下文时为 {@code null}
     */
    public static Long getTenantId() {
        Snapshot snapshot = SNAPSHOT.get();
        return snapshot == null ? null : snapshot.tenantId();
    }

    /**
     * 当前 token 的 {@code jti}。
     *
     * @return jti；无登录态时为 {@code null}
     */
    public static String getTokenId() {
        Snapshot snapshot = SNAPSHOT.get();
        return snapshot == null ? null : snapshot.tokenId();
    }

    /**
     * 是否存在租户上下文。
     *
     * @return {@code true} 表示本请求已绑定租户
     */
    public static boolean hasContext() {
        return SNAPSHOT.get() != null;
    }

    /**
     * 进入一层租户豁免（{@link IgnoreTenantAspect} 在方法执行前调用）。
     */
    public static void pushIgnore() {
        Integer depth = IGNORE_DEPTH.get();
        IGNORE_DEPTH.set(depth == null ? 1 : depth + 1);
    }

    /**
     * 退出一层租户豁免（{@link IgnoreTenantAspect} 在 {@code finally} 中调用）。
     *
     * <p>深度归零时 {@link ThreadLocal#remove()} 而不是置 0：让"未豁免"回到初始态，
     * 避免线程池复用下残留状态。
     *
     * <p>未压栈即弹栈（深度为 {@code null}）时静默忽略，不抛异常 ——
     * 本方法位于 {@code finally} 路径上，抛异常会覆盖掉真正的业务异常，得不偿失。
     */
    public static void popIgnore() {
        Integer depth = IGNORE_DEPTH.get();
        if (depth == null) {
            return;
        }
        if (depth <= 1) {
            IGNORE_DEPTH.remove();
        } else {
            IGNORE_DEPTH.set(depth - 1);
        }
    }

    /**
     * 当前是否处于租户豁免中（{@link TenantLineHandlerImpl} 据此跳过 tenant_id 注入）。
     *
     * @return {@code true} 表示本次查询免于租户条件注入
     */
    public static boolean isIgnored() {
        Integer depth = IGNORE_DEPTH.get();
        return depth != null && depth > 0;
    }

    /**
     * 清理本线程的全部上下文 —— <b>请求结束必须调用</b>（见类注释）。
     *
     * <p>登录态与豁免深度一并清掉：豁免深度是"本次调用"的状态，
     * 若因为异常路径残留，会让后续请求静默失去租户隔离。
     */
    public static void clear() {
        SNAPSHOT.remove();
        IGNORE_DEPTH.remove();
    }
}
