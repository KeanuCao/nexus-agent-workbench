package com.nexus.module.ai.gateway;

import com.nexus.module.ai.gateway.dto.ChatRequest;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 流式对话的编排端口：路由 → 提交到专用线程池 → 调 provider → 转帧 → 推 {@code SseEmitter}。
 *
 * <p>它与 {@link ModelRouter}（选谁）、{@link AiModelService}（怎么调）一起构成设计决策 D5 的三层。
 * 本层只做"把三件事串起来"，三件具体的事都不在这里：
 * <ul>
 *     <li>选模型 —— {@link ModelRouter}；</li>
 *     <li>调上游、解析上游的行协议 —— {@link AiModelService} 的实现；</li>
 *     <li>帧的状态机（meta 只发一次、终止帧唯一、断开即取消）—— 实现内部的会话对象。</li>
 * </ul>
 *
 * <h2>为什么是"返回 emitter"而不是"返回一段流"</h2>
 * {@code SseEmitter} 一交给容器，HTTP 响应头（{@code 200} + {@code text/event-stream}）就发出去了，
 * 此后<b>状态码无法再改</b>。这条分界线决定了所有失败的载体（设计 §3.3 的失败形态总表）：
 * 本方法<b>抛出的</b>异常走正常 {@code Result} 出口（HTTP 状态码还改得动），
 * 而工作线程上发生的一切失败只能写成 {@code event: error} 帧。
 *
 * @author nexus
 */
public interface ChatService {

    /**
     * 开一条流式对话。
     *
     * <p><b>调用方必须在请求线程上调用</b>，并在调用前把登录态抓成 {@link CallerContext}
     * （决策 D10，理由见该 record 的注释）。
     *
     * @param request 请求体（调用方已完成 Bean Validation：{@code messages} 非空、最后一条是 {@code user}）
     * @param caller  调用方身份快照（请求线程上抓取，供工作线程重建上下文）
     * @return 已登记的 SSE 出口；返回后响应头即已发出
     * @throws com.nexus.common.exception.BusinessException 路由失败（未知 {@code modelType} → 10200）
     * @throws TaskRejectedException 模型线程池已满 —— 开流前同步可判，由全局异常处理器
     *                               转成 <b>HTTP 503 + 20100</b>（设计 D8 / §3.3）
     */
    SseEmitter stream(ChatRequest request, CallerContext caller);

    /**
     * 调用方身份快照：把登录态从<b>请求线程</b>搬到<b>工作线程</b>（决策 D10）。
     *
     * <h2>为什么必须有它</h2>
     * {@code TenantContext} 是纯 {@code ThreadLocal}，{@code JwtAuthenticationFilter} 在
     * {@code finally} 里 {@code clear()} 过 —— 工作线程上<b>必然没有</b>租户上下文。
     * 在 {@code TenantLineHandlerImpl} 的 fail-closed 设计下，那里任何一次数据库访问都会直接抛异常。
     * 本阶段聊天链路不读库，所以这个坑本轮不会自曝；但阶段3 的 RAG 一定会踩上，
     * 届时它会表现成一个<b>没有租户上下文的 500</b>。现在做成本约 10 行。
     *
     * <h2>为什么带 {@code tokenId}</h2>
     * 三个字段一起搬，是为了让工作线程上的上下文与请求线程<b>完全等价</b>。
     * 只搬 userId/tenantId 的话，工作线程里的 {@code TenantContext.getTokenId()} 会悄悄变成
     * {@code null} —— "半份上下文"比"没有上下文"更难排查（读得到租户、却读不到 jti）。
     *
     * <p>字段一律可空（无登录态时全是 {@code null}）：本 record 只是搬运工，不承担鉴权判定 ——
     * 能不能进来由过滤器说了算。
     *
     * @param userId   用户 ID
     * @param tenantId 租户 ID（多租户隔离的依据）
     * @param tokenId  JWT 的 {@code jti}
     * @author nexus
     */
    record CallerContext(Long userId, Long tenantId, String tokenId) {
    }
}
