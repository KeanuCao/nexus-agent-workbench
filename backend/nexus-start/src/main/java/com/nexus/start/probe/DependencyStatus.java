package com.nexus.start.probe;

/**
 * 依赖探活状态。
 *
 * <p>枚举名即 JSON 取值 —— {@code "UP"} / {@code "DOWN"}（Jackson 默认按枚举名序列化），
 * 与设计文档 §5.3 契约、前端 {@code HealthStatus} 类型严格一致。
 *
 * <p>刻意不引入 Spring 的 {@code HealthStatus}（其取值含 OUT_OF_SERVICE / UNKNOWN）：
 * 契约只约定两种状态，收窄取值可避免前端出现未定义分支。
 *
 * @author nexus
 */
public enum DependencyStatus {

    /** 连通性探测通过。 */
    UP,

    /** 连通性探测失败（超时、拒绝连接、认证失败等）。 */
    DOWN
}
