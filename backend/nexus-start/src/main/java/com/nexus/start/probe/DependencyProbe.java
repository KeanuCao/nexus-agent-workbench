package com.nexus.start.probe;

/**
 * 依赖连通性探测：一个实现对应一个被探活的外部依赖（Postgres / Redis / Ollama）。
 *
 * <p>设计要点：
 * <ul>
 *     <li>探测结果必须来自真实交互 —— JDBC 取连接执行 {@code SELECT 1}、Redis 发 {@code PING}、
 *         Ollama 请求 {@code /api/version}，禁止用"配置存在"之类的间接判断顶替
 *         （设计文档 §5.3 明确要求"不造假 —— 这是面试演示链路的第一步"）；</li>
 *     <li>实现类必须自行吞掉可预期的异常并降级为 {@link DependencyStatus#DOWN}，
 *         不得向上抛出 —— 依赖不可用是健康检查的<b>正常输出</b>，不是接口故障；</li>
 *     <li>新增依赖探活只需实现本接口并注册为 Bean，{@code HealthService} 自动纳入，
 *         但返回体结构由 {@code HealthChecks} 记录显式定义（契约稳定性优先）。</li>
 * </ul>
 *
 * @author nexus
 */
public interface DependencyProbe {

    /**
     * 依赖名，取值即响应体 {@code data.checks} 中的字段名（postgres / redis / ollama）。
     *
     * @return 依赖名
     */
    String name();

    /**
     * 执行一次连通性探测。
     *
     * @return {@link DependencyStatus#UP} 表示通，{@link DependencyStatus#DOWN} 表示不通
     */
    DependencyStatus check();
}
