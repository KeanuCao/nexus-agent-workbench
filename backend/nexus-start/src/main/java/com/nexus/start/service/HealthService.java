package com.nexus.start.service;

import com.nexus.start.model.HealthReport;

/**
 * 健康检查服务：汇总各依赖的真实连通性探测。
 *
 * @author nexus
 */
public interface HealthService {

    /**
     * 执行一次健康检查。
     *
     * <p>本方法不抛异常：依赖不可用、探活实现异常等情况一律降级为
     * {@code checks.* = DOWN}，由调用方（Controller）据此决定 HTTP 状态码。
     *
     * @return 健康检查报告
     */
    HealthReport check();
}
