package com.nexus.infrastructure.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 请求侧安全配置：绑定 {@code nexus.security.*}（见 nexus-start 的 application.yml）。
 *
 * @author nexus
 */
@Component
@ConfigurationProperties(prefix = "nexus.security")
public class SecurityProperties {

    /**
     * 免鉴权路径（Ant 风格），{@link JwtAuthenticationFilter} 据此放行。
     *
     * <p><b>默认值即"必须免鉴权"的三条，每一条都有明确理由</b>（设计 §6.4）：
     * <ul>
     *     <li>{@code /api/auth/login}：登录本身当然不能要求先登录；</li>
     *     <li>{@code /api/health}：容器与 scripts/sh/up.sh、check-env.sh、check-health.sh 的探活口
     *         —— 给它加鉴权会让 0.3 已验收的三个脚本全线 FAIL；</li>
     *     <li>{@code /actuator/**}：docker-compose healthcheck 的探活口。</li>
     * </ul>
     *
     * <p><b>默认拒绝，不默认放行</b>：除本清单外一律需要 token。
     * 因此把清单配成空列表不会"放开鉴权"，而是"连探活口都要 token"——
     * 这是刻意的方向性选择（漏配的后果是拒绝服务，而不是裸奔）。
     *
     * <p>Java 侧保留与 yml 相同的默认值：yml 缺失或被误删时行为不退化。
     * 改白名单请<b>两处同步</b>（yml 只用于"可见与可覆盖"，不改变默认值语义）。
     */
    private List<String> whitelist = new ArrayList<>(List.of(
            "/api/auth/login",
            "/api/health",
            "/actuator/**"));

    public List<String> getWhitelist() {
        return whitelist;
    }

    public void setWhitelist(List<String> whitelist) {
        this.whitelist = whitelist;
    }
}
