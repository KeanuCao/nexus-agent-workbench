package com.nexus.infrastructure.config;

import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.handler.TenantLineHandler;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus 装配：多租户拦截器 + Mapper 扫描。
 *
 * <p><b>为什么 {@code @MapperScan} 必须有</b>：MyBatis 的 Mapper 接口不是 Spring 组件，
 * 不被 {@code @ComponentScan} 收录；没有它，注入 {@code UserMapper} 会在启动时报
 * "no bean of type ..."，且错误信息不会指出"你忘了扫描 Mapper"。扫描范围写成
 * {@code com.nexus.**.mapper}（Ant 风格），覆盖所有模块里约定的 {@code mapper} 包，
 * 后续新增模块无需再改这里。
 *
 * <p><b>拦截器的注册路径</b>：MyBatis-Plus 的自动配置（{@code MybatisPlusAutoConfiguration}）
 * 构造时注入 {@code ObjectProvider<Interceptor[]>}，即<b>容器里所有 {@code Interceptor} Bean
 * 都会被自动挂进 SqlSessionFactory</b> —— 因此在配置类里声明一个
 * {@link MybatisPlusInterceptor} Bean 即可，无需手工构造 SqlSessionFactory。
 *
 * <p>拦截器顺序：目前只有租户这一个 inner interceptor。将来加分页时
 * <b>租户必须排在分页之前</b>（MP 官方要求）—— 否则分页插件会先改写 SQL，
 * 租户条件加在结果外层，可能出现跨租户计数。加进去时按此顺序追加。
 *
 * @author nexus
 */
@Configuration
@MapperScan("com.nexus.**.mapper")
public class MybatisPlusConfig {

    /**
     * 多租户拦截器：对 SQL 自动追加 {@code tenant_id = ?}；豁免规则见
     * {@link com.nexus.infrastructure.tenant.TenantLineHandlerImpl}（fail-closed）。
     *
     * @param tenantLineHandler 租户条件提供者（容器中的 {@code TenantLineHandlerImpl}）
     * @return 装配好的 MyBatis-Plus 插件
     */
    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor(TenantLineHandler tenantLineHandler) {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new TenantLineInnerInterceptor(tenantLineHandler));
        return interceptor;
    }
}
