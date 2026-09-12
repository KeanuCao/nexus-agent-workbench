package com.nexus.start.probe;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.stereotype.Component;

/**
 * Redis 探活：发一次 {@code PING}，比对响应是否为 {@code PONG}。
 *
 * <p>命令超时由 {@code spring.data.redis.timeout}（application.yml，2s）统一控制，
 * Redis 不可达时会在超时后抛 {@link DataAccessException} 而非无限阻塞。
 *
 * @author nexus
 */
@Component
@Order(2)
public class RedisProbe implements DependencyProbe {

    /** 响应体 {@code data.checks} 中的字段名。 */
    public static final String NAME = "redis";

    private static final Logger log = LoggerFactory.getLogger(RedisProbe.class);

    /** PING 的正常响应（Redis 协议固定返回 PONG）。 */
    private static final String PONG = "PONG";

    private final RedisConnectionFactory redisConnectionFactory;

    public RedisProbe(RedisConnectionFactory redisConnectionFactory) {
        this.redisConnectionFactory = redisConnectionFactory;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public DependencyStatus check() {
        // RedisConnection 实现 AutoCloseable：连接必须归还，否则反复探活会耗尽连接池
        try (RedisConnection connection = redisConnectionFactory.getConnection()) {
            String response = connection.ping();
            if (PONG.equalsIgnoreCase(response)) {
                return DependencyStatus.UP;
            }
            log.warn("健康检查：redis PING 响应异常 response={}", response);
            return DependencyStatus.DOWN;
        } catch (DataAccessException ex) {
            log.warn("健康检查：redis 连通性探测失败 cause={} message={}",
                    ex.getClass().getSimpleName(), ex.getMessage());
            return DependencyStatus.DOWN;
        }
    }
}
