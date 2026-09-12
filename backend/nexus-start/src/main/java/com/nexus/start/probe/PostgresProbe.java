package com.nexus.start.probe;

import com.nexus.start.config.NexusHealthProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Postgres 探活：取一次 JDBC 连接并执行 {@code SELECT 1}。
 *
 * <p>为什么不用 {@code Connection#isValid}：{@code SELECT 1} 是显式的服务端往返，
 * 语义无歧义，面试演示时能在 PG 侧日志看到真实请求；{@code isValid} 的实现由驱动决定，
 * 部分驱动会走轻量判断，说服力弱。
 *
 * <p>超时双保险：连接获取受 Hikari {@code connection-timeout} 约束，
 * 查询本身受 {@code Statement#setQueryTimeout} 约束 —— 数据库僵死时健康检查也不会挂住。
 *
 * @author nexus
 */
@Component
@Order(1)
public class PostgresProbe implements DependencyProbe {

    /** 响应体 {@code data.checks} 中的字段名。 */
    public static final String NAME = "postgres";

    private static final Logger log = LoggerFactory.getLogger(PostgresProbe.class);

    private static final String PROBE_SQL = "SELECT 1";

    private final DataSource dataSource;

    /** 查询超时（秒）：由 nexus.health.probe-timeout-ms 换算，最小值 1 秒（0 表示无限制，不可接受）。 */
    private final int queryTimeoutSeconds;

    public PostgresProbe(DataSource dataSource, NexusHealthProperties properties) {
        this.dataSource = dataSource;
        this.queryTimeoutSeconds = Math.max(1, properties.getProbeTimeoutMs() / 1000);
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public DependencyStatus check() {
        try (Connection connection = dataSource.getConnection()) {
            try (Statement statement = connection.createStatement()) {
                statement.setQueryTimeout(queryTimeoutSeconds);
                try (ResultSet resultSet = statement.executeQuery(PROBE_SQL)) {
                    return resultSet.next() ? DependencyStatus.UP : DependencyStatus.DOWN;
                }
            }
        } catch (SQLException ex) {
            // 依赖不可用属健康检查的正常输出，记 warn 即可（不是程序缺陷，无需堆栈）
            log.warn("健康检查：postgres 连通性探测失败 cause={} message={}",
                    ex.getClass().getSimpleName(), ex.getMessage());
            return DependencyStatus.DOWN;
        }
    }
}
