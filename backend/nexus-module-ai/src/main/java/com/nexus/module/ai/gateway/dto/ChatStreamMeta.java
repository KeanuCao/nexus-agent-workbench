package com.nexus.module.ai.gateway.dto;

import com.nexus.module.ai.gateway.ModelType;

/**
 * {@code event: meta} 帧的 {@code data}（契约：{@code docs/api/README.md} §6.2）。
 *
 * <p>它是<b>第一帧、仅有且必有一帧</b>，在首个 {@code delta} 之前发出（时序规则 1）——
 * 保证"模型在有内容之前就已经定下"，否则前端无法解释自己收到的是什么模型的输出。
 *
 * <p>出参用 record：由本项目自己构造、不可变更安全，也无反序列化顾虑
 * （与 {@code ChatMessage} 用可变 POJO 的分工一致）。Jackson 按组件声明顺序序列化，
 * 无需 {@code @JsonPropertyOrder} —— 这里的字段顺序与契约示例逐字一致。
 *
 * @param modelType 实际使用的模型类型（枚举名即线上取值：{@code OLLAMA} / {@code DEEPSEEK}）
 * @param model     上游<b>真实模型名</b>（{@code qwen2.5:7b} / {@code deepseek-chat}）；
 *                  验收标准 2.2-1 判定"是否命中对应实现"靠的就是它
 * @param servedBy  谁选的模型：{@code user-selected}（请求里带了 modelType）/
 *                  {@code default}（请求里没带，后端取了默认模型）。
 *                  取值来自 {@code ModelRouter.ServedBy}，这里用 {@code String} 而不是那个枚举：
 *                  <b>该枚举的线上取值不是枚举名</b>，直接序列化会得到 {@code USER_SELECTED}；
 *                  而把 DTO 与序列化注解（{@code @JsonValue}）绑在一起是本项目 DTO 至今没有的耦合
 * @author nexus
 */
public record ChatStreamMeta(
        ModelType modelType,
        String model,
        String servedBy) {
}
