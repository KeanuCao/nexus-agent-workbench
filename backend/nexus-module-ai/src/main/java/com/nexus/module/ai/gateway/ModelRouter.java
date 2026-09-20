package com.nexus.module.ai.gateway;

import com.nexus.module.ai.gateway.dto.ChatRequest;

/**
 * 路由：<b>这次该调谁</b>（决策 D5）。
 *
 * <p>它与 {@link AiModelService}（<b>怎么调</b>）分开，是为了"后端自动选模型"那一刀
 * （设计 §10.1）：自动路由上线时只需新增一个本接口的实现，provider 实现与前端一行不动。
 * 本轮只有一个实现 {@code UserSelectedModelRouter}（按用户传来的 modelType），
 * 多这一个接口 + 一个类约 30 行，是<b>刻意预留，不是过度设计</b>。
 *
 * <p>路由发生在<b>请求线程</b>上、开流之前：它抛出的业务异常（如 10200）能走正常
 * {@code Result} 出口，HTTP 状态码也还改得动。一旦 emitter 交给了容器，
 * 这条路就断了（见设计 §3.3 的失败形态总表）—— 所以"选谁"必须早于"怎么调"。
 *
 * @author nexus
 */
public interface ModelRouter {

    /**
     * 决定本次请求由哪个模型实现来回答。
     *
     * @param request 请求体（带上完整 {@code messages} 是为了将来按内容自动选模型；
     *                本轮只读 {@code modelType}）
     * @return 路由结果：实现 + 能力自述 + 谁选的
     */
    Decision route(ChatRequest request);

    /**
     * 路由结果。
     *
     * <p>{@code descriptor} 与 {@code service.descriptor()} 是同一份数据，这里随决策一并带出，
     * 是<b>刻意的重复</b>：调用方（{@code ChatService}）在一次请求里要用它三处
     * （日志的 {@code model=}、{@code meta} 帧、以及传给 {@code stream(...)} 的入参），
     * 让它再绕回注册表查一遍只会多出一处可能查错的地方。
     *
     * @param service    选定的 provider 实现
     * @param descriptor 该实现的能力自述（模型名会用进 {@code meta} 帧）
     * @param servedBy   这个选择是谁做的（会用进 {@code meta} 帧）
     * @author nexus
     */
    record Decision(AiModelService service, ModelDescriptor descriptor, ServedBy servedBy) {
    }

    /**
     * 模型是谁选的 —— {@code meta} 帧里的 {@code servedBy}。
     *
     * <p>枚举名与线上取值刻意分开：线上是小写连字符（{@code user-selected}），
     * 枚举名是大写下划线。用法固定为 {@code getWireValue()}，<b>不要</b>直接序列化本枚举。
     *
     * <p>刻意<b>不</b>预留 {@code FALLBACK} 取值：降级链本轮明确不做（决策 D7），
     * 预留缝 ≠ 提前实现 —— 等真做时再加，那时它才会有生产者。
     */
    enum ServedBy {

        /** 用户在请求里指定了 {@code modelType}。 */
        USER_SELECTED("user-selected"),

        /** 用户没指定，由后端取默认模型（{@code nexus.ai.default-model}）。 */
        DEFAULT("default");

        private final String wireValue;

        ServedBy(String wireValue) {
            this.wireValue = wireValue;
        }

        /**
         * 线上取值（写进 {@code meta.servedBy} 的那个字符串）。
         *
         * @return {@code user-selected} / {@code default}
         */
        public String getWireValue() {
            return wireValue;
        }
    }
}
