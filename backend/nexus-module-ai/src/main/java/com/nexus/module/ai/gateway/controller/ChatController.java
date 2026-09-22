package com.nexus.module.ai.gateway.controller;

import com.nexus.infrastructure.tenant.TenantContext;
import com.nexus.module.ai.gateway.ChatService;
import com.nexus.module.ai.gateway.dto.ChatRequest;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 对话接口（契约：{@code docs/api/README.md} §6 / {@code docs/api/openapi.yaml}）。
 *
 * <p>路径约定：不在类上挂 {@code @RequestMapping}，完整路径与 {@code produces}/{@code consumes}
 * 都写在方法注解上（CLAUDE.md 宪法要求显式声明接口契约，与 {@code HealthController} 同一风格）。
 *
 * <h2>失败出口一览（谁在哪里处置）</h2>
 * <table border="1">
 *     <caption>本接口的四种失败与它们的载体</caption>
 *     <tr><th>失败</th><th>载体</th><th>HTTP</th><th>code</th></tr>
 *     <tr><td>没带 token / token 失效</td><td>过滤器直接写 {@code Result}</td><td>401</td><td>40100/40101/40102</td></tr>
 *     <tr><td>请求体不合法（{@code @Valid}）</td><td>全局异常处理器</td><td>200</td><td>40001</td></tr>
 *     <tr><td>未知 {@code modelType}</td><td>路由（请求线程）抛业务异常</td><td>200</td><td>10200</td></tr>
 *     <tr><td>模型线程池已满</td><td>全局异常处理器</td><td><b>503</b></td><td>20100</td></tr>
 *     <tr><td>上游不可达 / 超时 / 报错</td><td>{@code event: error} 帧</td><td>*(已是 200)*</td><td>20100</td></tr>
 * </table>
 * 前四行都发生在<b>开流前</b>（本方法尚未返回），所以还能用普通 {@code Result} + 状态码；
 * 一旦 emitter 返回、响应头发出，同样的 20100 就只能写成帧 —— 这条分界线是设计 §3.3 的全部内容。
 *
 * @author nexus
 */
@RestController
public class ChatController {

    private final ChatService chatService;

    public ChatController(ChatService chatService) {
        this.chatService = chatService;
    }

    /**
     * {@code POST /api/chat/stream}：把会话历史全量上送，以 SSE 事件流逐片返回回答。
     *
     * <p>{@code produces} / {@code consumes} 显式声明（宪法要求）：前者不仅是文档 ——
     * Spring 会据此把响应 {@code Content-Type} 设为 {@code text/event-stream}（**裸值，不带
     * {@code ;charset=UTF-8}**：SSE 恒为 UTF-8，该参数按规范只是为兼容遗留服务端而保留，本项目
     * 刻意不加；2026-09-21 实测），而**前端就是靠它**区分"事件流"与"开流前的普通 Result"
     * （20100 有两种载体，见 README §6.3 —— 前端用 {@code includes('application/json')} 判，
     * 不能全等：失败形态的 json 带 charset，两边形态本就不一致）。
     *
     * @param request 请求体（{@code messages} + 可空的 {@code modelType}）
     * @return SSE 出口（返回后响应头即已发出）
     */
    @PostMapping(value = "/api/chat/stream",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE,
            consumes = MediaType.APPLICATION_JSON_VALUE)
    public SseEmitter stream(@Valid @RequestBody ChatRequest request) {
        // ★ D10 上下文透传：TenantContext 是纯 ThreadLocal，过滤器在 finally 里 clear 过，
        //   工作线程上**必然没有**它 —— 必须在返回 emitter 之前、也就是还在请求线程上时，
        //   把登录态抓成局部变量交给工作线程重建。三个字段一起搬，理由见 CallerContext 的注释。
        ChatService.CallerContext caller = new ChatService.CallerContext(
                TenantContext.getUserId(),
                TenantContext.getTenantId(),
                TenantContext.getTokenId());
        return chatService.stream(request, caller);
    }
}
