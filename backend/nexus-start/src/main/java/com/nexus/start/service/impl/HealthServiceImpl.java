package com.nexus.start.service.impl;

import com.nexus.start.config.NexusHealthProperties;
import com.nexus.start.model.HealthChecks;
import com.nexus.start.model.HealthReport;
import com.nexus.start.probe.DependencyProbe;
import com.nexus.start.probe.DependencyStatus;
import com.nexus.start.probe.OllamaProbe;
import com.nexus.start.probe.PostgresProbe;
import com.nexus.start.probe.RedisProbe;
import com.nexus.start.service.HealthService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 健康检查服务实现。
 *
 * <p>串行探测而非并行：单次探测超时上限 2s（nexus.health.probe-timeout-ms），
 * 三个依赖最坏耗时 6s，可接受；并行会增加线程池与超时管理的复杂度，
 * 而健康检查本身对延迟不敏感 —— 这是有意的取舍。
 *
 * @author nexus
 */
@Service
public class HealthServiceImpl implements HealthService {

    private static final Logger log = LoggerFactory.getLogger(HealthServiceImpl.class);

    /** 探活实现列表：Spring 按 {@code @Order} 顺序注入，新增依赖探活只需实现 DependencyProbe。 */
    private final List<DependencyProbe> probes;

    private final NexusHealthProperties healthProperties;

    public HealthServiceImpl(List<DependencyProbe> probes, NexusHealthProperties healthProperties) {
        this.probes = probes;
        this.healthProperties = healthProperties;
    }

    @Override
    public HealthReport check() {
        Map<String, DependencyStatus> results = new LinkedHashMap<>();
        for (DependencyProbe probe : probes) {
            results.put(probe.name(), safelyCheck(probe));
        }

        // 用探活实现类上的常量做 key 查找，避免字符串字面量手写错位
        HealthChecks checks = new HealthChecks(
                statusOf(results, PostgresProbe.NAME),
                statusOf(results, RedisProbe.NAME),
                statusOf(results, OllamaProbe.NAME));

        HealthReport report = HealthReport.of(
                healthProperties.getServiceName(), healthProperties.getVersion(), checks);

        log.info("健康检查完成：service={} version={} overall={} checks={}",
                report.service(), report.version(), report.status(), results);
        return report;
    }

    /**
     * 探活执行的安全网：探活实现自身的意外异常（缺陷、空指针、第三方库异常）
     * 不得把健康检查接口打成 500 —— 那会让"依赖不可用"与"程序坏了"无法区分。
     *
     * @param probe 探活实现
     * @return 探测状态，异常时降级为 DOWN
     */
    private DependencyStatus safelyCheck(DependencyProbe probe) {
        try {
            return probe.check();
        } catch (RuntimeException ex) {
            log.error("健康检查：依赖探活执行异常 dep={}", probe.name(), ex);
            return DependencyStatus.DOWN;
        }
    }

    /**
     * 按依赖名取探测结果；探活 Bean 缺失时按不可用处理（宁可误报 DOWN，不可漏报 UP）。
     *
     * @param results        以依赖名为 key 的探测结果
     * @param dependencyName 依赖名
     * @return 探测状态
     */
    private DependencyStatus statusOf(Map<String, DependencyStatus> results, String dependencyName) {
        return results.getOrDefault(dependencyName, DependencyStatus.DOWN);
    }
}
