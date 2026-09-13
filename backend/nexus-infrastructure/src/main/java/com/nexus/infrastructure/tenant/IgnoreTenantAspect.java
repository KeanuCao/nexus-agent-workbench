package com.nexus.infrastructure.tenant;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;

/**
 * {@link IgnoreTenant} 的生效机制：在 Mapper 方法执行期间把"本查询豁免租户条件"压入
 * {@link TenantContext}，方法返回（或抛异常）后弹栈。
 *
 * <p><b>两个刻意的实现选择（都是踩过坑的地方）：</b>
 * <ol>
 *     <li><b>切点是 {@code execution(* com.nexus..mapper..*(..))} 而不是 {@code @annotation(...)}。</b>
 *         MyBatis 的 Mapper 是 <b>JDK 动态代理</b>（{@code MapperProxy}），Spring AOP 又在其外套一层代理；
 *         {@code @annotation} 依赖"当前执行方法上能匹配到注解"的语义，在桥接方法/代理类上并不稳定。
 *         改成"先切 Mapper 包下的全部方法，再用反射自己读注解"，行为只取决于
 *         {@link Method#getAnnotation(Class)} 的确定性语义。</li>
 *     <li><b>注解从接口方法上读，不读目标类。</b>
 *         目标对象是 {@code com.sun.proxy.$ProxyNN}，其类上不可能有业务注解 ——
 *         必须用 {@link MethodSignature#getMethod()}（即接口方法），
 *         再用 {@link AnnotatedElementUtils#findMergedAnnotation} 兜住"注解标在父接口/由元注解派生"的情况。</li>
 * </ol>
 *
 * <p>豁免会 {@code log.warn} 留痕：租户隔离的每一次破例都应当可追溯
 * （设计决策 D4 的收益点，也是面试可讲的审计思路）。
 *
 * <p>本切面对<b>所有</b> Mapper 方法生效，因此未标 {@link IgnoreTenant} 的方法是"直接放行"的
 * 快路径：只多一次反射查注解的开销，不会触发任何 ThreadLocal 写入。
 *
 * @author nexus
 */
@Aspect
@Component
public class IgnoreTenantAspect {

    private static final Logger log = LoggerFactory.getLogger(IgnoreTenantAspect.class);

    /**
     * 环绕 Mapper 方法：命中 {@link IgnoreTenant} 则压/弹豁免计数器，否则原样放行。
     *
     * @param joinPoint 连接点（Mapper 接口方法）
     * @return 原方法的返回值
     * @throws Throwable 原方法抛出的异常（不包装、不吞掉）
     */
    @Around("execution(* com.nexus..mapper..*(..))")
    public Object aroundMapperMethod(ProceedingJoinPoint joinPoint) throws Throwable {
        IgnoreTenant ignoreTenant = resolveAnnotation(joinPoint);
        if (ignoreTenant == null) {
            return joinPoint.proceed();
        }

        TenantContext.pushIgnore();
        try {
            MethodSignature signature = (MethodSignature) joinPoint.getSignature();
            log.warn("租户隔离豁免：{}.{}，原因：{}",
                    signature.getDeclaringType().getSimpleName(), signature.getName(), ignoreTenant.value());
            return joinPoint.proceed();
        } finally {
            // 必须 finally：豁免期间抛异常（如 SQL 报错）同样要弹栈，
            // 否则该线程后续请求会静默失去租户隔离
            TenantContext.popIgnore();
        }
    }

    /**
     * 解析方法（或方法所在接口）上的 {@link IgnoreTenant} 注解。
     *
     * @param joinPoint 连接点
     * @return 注解实例；未标注时返回 {@code null}
     */
    private IgnoreTenant resolveAnnotation(ProceedingJoinPoint joinPoint) {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();

        IgnoreTenant annotation = AnnotatedElementUtils.findMergedAnnotation(method, IgnoreTenant.class);
        if (annotation != null) {
            return annotation;
        }
        // 退化路径：整个 Mapper 接口标注（如某张表整体无租户维度）
        return AnnotatedElementUtils.findMergedAnnotation(method.getDeclaringClass(), IgnoreTenant.class);
    }
}
