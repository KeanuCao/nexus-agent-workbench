package com.nexus.start.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.nexus.start.probe.DependencyStatus;

import java.time.OffsetDateTime;

/**
 * 健康检查报告 —— 响应体 {@code Result.data} 的完整定义（设计文档 §5.3 契约）。
 *
 * <p>字段顺序即契约顺序：status / service / version / timestamp / checks
 * （Jackson 对 record 按组件声明顺序序列化，无需 {@code @JsonPropertyOrder}）。
 *
 * @param status    总体状态：全部依赖 UP 才是 UP，任一 DOWN 即为 DOWN
 * @param service   服务名，取自 {@code nexus.health.service-name}
 * @param version   版本号，取自 {@code nexus.health.version}（yml 中由 Maven 资源过滤注入 pom 版本）
 * @param timestamp 生成时刻，ISO-8601 带时区偏移（例：{@code 2026-09-03T10:00:00+08:00}）；
 *                  偏移量取服务端默认时区 —— 容器内通常为 UTC（{@code +00:00}）
 * @param checks    各依赖的真实连通性探测结果
 * @author nexus
 */
public record HealthReport(
        DependencyStatus status,
        String service,
        String version,
        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ssXXX")
        OffsetDateTime timestamp,
        HealthChecks checks) {

    /**
     * 由依赖探测结果组装报告（总体状态自动推导）。
     *
     * @param service 服务名
     * @param version 版本号
     * @param checks  依赖探测结果，不可为 {@code null}
     * @return 健康检查报告
     */
    public static HealthReport of(String service, String version, HealthChecks checks) {
        DependencyStatus overall = checks.allUp() ? DependencyStatus.UP : DependencyStatus.DOWN;
        return new HealthReport(overall, service, version, OffsetDateTime.now(), checks);
    }

    /**
     * 总体状态是否可用（方法名非 getXxx/isXxx 形式，不会被序列化为 JSON 字段）。
     *
     * @return 总体状态为 UP 时返回 {@code true}
     */
    public boolean allUp() {
        return status == DependencyStatus.UP;
    }
}
