package com.nexus.module.ai.gateway.dto;

/**
 * {@code event: done} 帧的 {@code data}（契约：{@code docs/api/README.md} §6.2）。
 *
 * <p>正常结束帧，与 {@code error} <b>互斥</b>：一次流里两者有且仅有一个，
 * 发出后服务端立即 {@code complete()}（时序规则 2）。
 *
 * <p>{@code finishReason} 用 {@code String} + 常量而不是枚举，理由同
 * {@code ChatStreamMeta.servedBy}：线上取值是小写的 {@code stop}，与枚举名
 * （{@code STOP}）不一致，用枚举就得给 DTO 挂 {@code @JsonValue}。
 *
 * @param finishReason 结束原因，取值见本类的 {@code FINISH_*} 常量
 * @param deltaCount   本次共发出多少帧 {@code delta}
 * @param durationMs   从开始到结束的毫秒数（含上游调用与每帧写出）
 * @author nexus
 */
public record ChatStreamDone(
        String finishReason,
        int deltaCount,
        long durationMs) {

    /** 模型正常结束（上游给出 {@code stop} 或 NDJSON 的 {@code done=true}）。 */
    public static final String FINISH_STOP = "stop";

    /** 触达 token 上限（上游给出 {@code length}）。 */
    public static final String FINISH_LENGTH = "length";

    /**
     * 被<b>服务端软上限</b>截断（运维口径的"我们主动停了它"）。
     *
     * <p>它<b>不</b>由 provider 给出：provider 只转述上游的结束原因，而
     * {@code nexus.ai.soft-timeout-ms} 到点是网关自己的定时任务发起的收尾
     * —— 这是"服务端主动截断"与"上游自己结束"在契约上的唯一区分点，
     * 排查"为什么答到一半就断了"时必须能一眼看出是这个原因。
     */
    public static final String FINISH_TIMEOUT = "timeout";
}
