package com.nexus.infrastructure.tenant;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 声明某个 Mapper 方法<b>豁免</b>多租户条件注入（本项目的 D4 决策）。
 *
 * <p>为什么不用 MyBatis-Plus 自带的 {@code @InterceptorIgnore(tenantLine = "true")}：
 * 那是<b>插件总开关</b>（一个注解还能关掉分页、乐观锁等），命名不表达"忽略租户"这一具体意图，
 * 颗粒度也不可控；而自研注解可以带上 {@link #value()} 写明<b>豁免原因</b>，
 * 由 {@link IgnoreTenantAspect} 在豁免时 {@code log.warn} 留痕 ——
 * 「租户隔离的每一次破例都有审计记录」是这套机制真正的价值。
 *
 * <p>用法（原因必填，写不出原因的豁免通常就不该存在）：
 * <pre>
 * &#64;IgnoreTenant("登录按全局唯一的 username 检索用户，此刻尚无租户上下文")
 * User selectByUsername(&#64;Param("username") String username);
 * </pre>
 *
 * <p>⚠️ 本注解只对 <b>{@code com.nexus..mapper..} 包下的 Mapper 方法</b>生效
 * （切点范围见 {@link IgnoreTenantAspect}）—— 标在 Service 方法上不会有任何效果。
 *
 * @author nexus
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface IgnoreTenant {

    /**
     * 豁免原因（必填）。
     *
     * <p>会被切面打进 {@code log.warn}，因此请写"为什么这个查询不该带租户条件"，
     * 而不是复述方法名 —— 一年后排查问题的人（很可能是你自己）需要的是原因。
     *
     * @return 豁免原因
     */
    String value();
}
