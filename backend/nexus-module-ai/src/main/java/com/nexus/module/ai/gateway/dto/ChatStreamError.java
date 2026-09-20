package com.nexus.module.ai.gateway.dto;

/**
 * {@code event: error} 帧的 {@code data}（契约：{@code docs/api/README.md} §6.2）。
 *
 * <p><b>为什么需要这个类</b>：失败帧的 {@code data} 形状被契约<b>定死为对象</b>，
 * 而不是 {@code null}。而 {@code Result.failure(code, msg)} 产生的 {@code data} 就是 {@code null}
 * —— 那样前端只知道"失败了"，不知道"已经吐了多少字"，也就无法决定保留还是回滚这一轮。
 * 因此错误帧必须走 {@code Result.failure(code, msg, new ChatStreamError(deltaCount))}。
 *
 * <p>只带 {@code deltaCount} 一个字段是刻意的：错误码与文案在外层的 {@code Result} 里
 * （{@code code} = 20100 上游不可用 / 50000 未预期异常），这里只补"已经吐了多少"这一条
 * Result 表达不了的信息。多加字段就是重复与漂移。
 *
 * @param deltaCount 出错前已经发出的 {@code delta} 帧数；一个都没发出时为 {@code 0}
 * @author nexus
 */
public record ChatStreamError(int deltaCount) {
}
