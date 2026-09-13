package com.nexus.infrastructure.security;

import com.nexus.common.exception.SystemException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 登录态白名单（Redis）：JWT 之外的第二道凭据 —— 也是"能登出"的唯一实现方式。
 *
 * <p><b>为什么有了 JWT 还要 Redis（设计决策 D1）</b>：纯 JWT 是<b>无状态</b>的，
 * 签发即有效到过期 —— 想撤销只能等它自然过期，做不了登出、做不了强制下线。
 * 而验收项明确要求「Token 与 Redis 联动，登出即失效」，所以按下述方式组合：
 * <pre>
 * 登录：签发 JWT（带 jti）+ SET nexus:auth:token:{jti} = 用户信息 EX 7200
 * 请求：JWT 校验通过 ≠ 有效，还要 EXISTS nexus:auth:token:{jti}
 * 登出：DEL nexus:auth:token:{jti}   ← 同一个 token 立刻失效，无需等过期
 * </pre>
 * 代价是每次请求多一次 Redis 读（本机 &lt;1ms）；收益是登出/强制下线真的可用。
 *
 * <p><b>fail-closed</b>：Redis 不可用时 {@link #exists(String)} 会抛
 * {@code DataAccessException}，由 {@code JwtAuthenticationFilter} 统一转成 401 ——
 * 即"宁可让所有人重新登录，也不放过任何无法验证的凭据"。
 * 这条取舍已登记在设计文档 §10 风险 1（半挂环境下的表现是"谁都登不进"，
 * 排查顺序应先看 {@code checks.redis}）。
 *
 * <p>value 存什么：<b>只是给人看的</b>（{@code redis-cli get} 时能认出是谁），
 * 鉴权判据是"键是否存在"，不解析 value。真正的用户数据一律回表取。
 *
 * @author nexus
 */
@Component
public class TokenStore {

    private static final Logger log = LoggerFactory.getLogger(TokenStore.class);

    /** 键前缀：{@code nexus:auth:token:{jti}}（与设计 §3 D1 逐字一致，便于运维识别与批量清理）。 */
    private static final String KEY_PREFIX = "nexus:auth:token:";

    private final StringRedisTemplate redisTemplate;

    public TokenStore(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * 登记一个登录态（登录成功时调用）。
     *
     * @param tokenId    JWT 的 {@code jti}
     * @param value      供人查看的用户信息（如 {@code userId=1,tenantId=1,username=admin}）
     * @param ttlSeconds 存活秒数，<b>必须与 JWT 有效期一致</b>（否则会出现
     *                   "token 已过期但白名单还在"或反过来的不一致状态）
     */
    public void save(String tokenId, String value, long ttlSeconds) {
        if (ttlSeconds <= 0) {
            throw new SystemException("登录态 TTL 非法（应为正数）：nexus.jwt.expire-seconds=" + ttlSeconds);
        }
        redisTemplate.opsForValue().set(buildKey(tokenId), value, Duration.ofSeconds(ttlSeconds));
    }

    /**
     * 该 {@code jti} 是否仍在白名单中。
     *
     * @param tokenId JWT 的 {@code jti}
     * @return {@code true} 表示登录态仍然有效
     * @throws org.springframework.dao.DataAccessException Redis 不可用（fail-closed，见类注释）
     */
    public boolean exists(String tokenId) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(buildKey(tokenId)));
    }

    /**
     * 注销一个登录态（登出时调用）。
     *
     * <p>幂等：删除不存在的键不报错，重复登出的结果是同一个（这是回调网络重试友好的语义）。
     *
     * @param tokenId JWT 的 {@code jti}
     */
    public void remove(String tokenId) {
        Boolean deleted = redisTemplate.delete(buildKey(tokenId));
        log.info("登出：删除登录态 jti={} 结果={}", tokenId, deleted);
    }

    /**
     * 组装 Redis 键。
     *
     * @param tokenId jti
     * @return {@code nexus:auth:token:{jti}}
     */
    private String buildKey(String tokenId) {
        return KEY_PREFIX + tokenId;
    }
}
