package com.nexus.module.system.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 口令编码器装配。
 *
 * <p><b>只引 {@code spring-security-crypto}，不引 {@code spring-boot-starter-security}</b>
 * （设计决策 D5）：完整 Security starter 会把<b>整条请求链</b>接管
 * （默认全拦截 + 自带的过滤器链 + 一堆需要改写的默认行为），
 * 而本项目要的是"自研 JWT 过滤器 + 五模块骨架"，两者方案冲突、配置成本远大于收益。
 * 只取 {@code BCryptPasswordEncoder} 一个类是最小侵入。
 *
 * <p><b>为什么用 BCrypt 而不是自研 SHA-256 加盐</b>：口令哈希要对"加盐、迭代次数、
 * 比对时的时序安全"三件事负责，自研等于把这三件都自己兜 —— 是典型的负面信号。
 * BCrypt 内置随机盐与可调工作因子，且 {@code matches()} 内部用常量时间比较。
 *
 * <p>强度取 {@link BCryptPasswordEncoder} 默认值（10 轮）：与
 * db-patch/202609131010_初始化用户表.sql 里种子哈希的生成参数一致
 * （{@code $2a$10$...}）—— 两处必须一致，否则种子账号登不进来。
 *
 * @author nexus
 */
@Configuration
public class PasswordEncoderConfig {

    /**
     * 口令编码器 Bean（以接口类型暴露，便于将来换算法而不改调用方）。
     *
     * @return BCrypt 实现（10 轮，随机盐）
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
