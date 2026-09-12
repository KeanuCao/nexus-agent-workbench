package com.nexus.start.model;

import com.nexus.start.probe.DependencyStatus;

import java.util.ArrayList;
import java.util.List;

/**
 * 依赖探活结果集合 —— 响应体中 {@code data.checks} 的完整定义。
 *
 * <p>为什么用 record 显式列字段而不是 {@code Map<String, String>}：
 * 记录组件名即 JSON 字段名，OpenAPI 契约能精确到"postgres / redis / ollama 三个字段，
 * 取值 UP 或 DOWN"；若用 Map，前端只能看到模糊的 additionalProperties，无法生成强类型客户端。
 * 代价是新增依赖需同步本记录 —— 契约稳定性优先于扩展便利性，这是有意的取舍。
 *
 * @param postgres Postgres 连通性（JDBC SELECT 1）
 * @param redis    Redis 连通性（PING / PONG）
 * @param ollama   Ollama 可用性（GET /api/version）
 * @author nexus
 */
public record HealthChecks(DependencyStatus postgres,
                           DependencyStatus redis,
                           DependencyStatus ollama) {

    /**
     * 是否全部依赖可用。
     *
     * @return 三个依赖均 UP 时返回 {@code true}
     */
    public boolean allUp() {
        return postgres == DependencyStatus.UP
                && redis == DependencyStatus.UP
                && ollama == DependencyStatus.UP;
    }

    /**
     * 不可用依赖的名称列表，用于拼装 503 响应体的提示信息。
     *
     * @return 例如 {@code ["postgres", "ollama"]}；全部可用时为空列表
     */
    public List<String> downNames() {
        List<String> downNames = new ArrayList<>(3);
        if (postgres == DependencyStatus.DOWN) {
            downNames.add("postgres");
        }
        if (redis == DependencyStatus.DOWN) {
            downNames.add("redis");
        }
        if (ollama == DependencyStatus.DOWN) {
            downNames.add("ollama");
        }
        return downNames;
    }
}
