package com.nexus.infrastructure.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * JWT 配置：绑定 {@code nexus.jwt.*}（见 nexus-start 的 application.yml）。
 *
 * <p>绑定的两种写法在本项目里的分工：本类用 {@code @Component + @ConfigurationProperties}
 * 自注册（配置与实现同模块，模块内自洽）；nexus-start 的 {@code NexusHealthProperties}
 * 由启动类 {@code @EnableConfigurationProperties} 显式登记。两者都能被
 * Spring Boot 的 {@code ConfigurationPropertiesBindingPostProcessor} 正常绑定。
 *
 * @author nexus
 */
@Component
@ConfigurationProperties(prefix = "nexus.jwt")
public class JwtProperties {

    /**
     * 签名密钥（HS256 要求 <b>至少 32 字节 / 256 位</b>，短了 jjwt 会直接抛 WeakKeyException）。
     *
     * <p>yml 里写成 {@code ${NEXUS_JWT_SECRET:dev 默认值}}：容器/CI 用环境变量注入真实密钥，
     * 本地开发用默认值即可起。默认值是<b>随仓库公开的演示值</b>，任何真实环境都必须覆盖
     * —— 否则等于把签名密钥交给所有能看到代码的人。
     */
    private String secret;

    /**
     * token 有效期（秒），同时也是 Redis 白名单记录的 TTL。
     *
     * <p>取 7200（2 小时）：演示够用，又能现场演示"过期 → 401 → 重新登录"。
     * 刻意不做刷新 token / 滑动续期（设计 §11 明确不做）。
     */
    private long expireSeconds = 7200;

    public String getSecret() {
        return secret;
    }

    public void setSecret(String secret) {
        this.secret = secret;
    }

    public long getExpireSeconds() {
        return expireSeconds;
    }

    public void setExpireSeconds(long expireSeconds) {
        this.expireSeconds = expireSeconds;
    }
}
