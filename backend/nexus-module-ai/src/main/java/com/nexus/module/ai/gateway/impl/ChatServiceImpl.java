package com.nexus.module.ai.gateway.impl;

import com.nexus.common.exception.BusinessException;
import com.nexus.common.result.ResultCode;
import com.nexus.infrastructure.tenant.TenantContext;
import com.nexus.module.ai.gateway.ChatService;
import com.nexus.module.ai.gateway.ModelRouter;
import com.nexus.module.ai.gateway.config.AiProperties;
import com.nexus.module.ai.gateway.config.AsyncConfig;
import com.nexus.module.ai.gateway.dto.ChatRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;
import java.util.concurrent.ScheduledFuture;

/**
 * 流式对话编排的实现（决策 D5 的第三层，见 {@link ChatService} 的类注释）。
 *
 * <h2>线程模型（决策 D8：同步阻塞调用 + 专用有界线程池）</h2>
 * <pre>
 * Tomcat 请求线程：路由 → 提交任务 → 返回 emitter（★ 此刻响应头已发出，状态码再也改不动）
 * 工作线程（chatStreamExecutor）：调上游 → 读分片 → 写 delta / done / error
 * 定时器线程（chatTimeoutScheduler）：软上限到点 → 写 done(timeout)
 * </pre>
 * 每个进行中的流占<b>一个工作线程</b>直到结束，池大小即并发流上限；池满立刻拒绝
 * （{@code AbortPolicy} + 容量 0），抛出的 {@code TaskRejectedException} 由全局异常处理器
 * 转成 HTTP 503 + 20100 —— 那一刻尚未开流，状态码还改得动，这是"全异步"口径下唯一还能给 503 的失败点。
 *
 * <h2>异常三分（CLAUDE.md 宪法）</h2>
 * <ul>
 *     <li>{@link BusinessException}：上游不可达/超时/报错（20100）→ 写 {@code error} 帧，<b>不向上抛</b>；</li>
 *     <li>其他 {@code RuntimeException}：程序缺陷 → 记 ERROR 堆栈 + 写 50000 的 {@code error} 帧
 *         —— 与"上游真的挂了"必须分得开，否则排查方向会被带偏；</li>
 *     <li>{@code TaskRejectedException}：唯一允许逃出本类的异常（开流前，交由全局处理器出 503）。</li>
 * </ul>
 *
 * @author nexus
 */
@Service
public class ChatServiceImpl implements ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatServiceImpl.class);

    private final ModelRouter modelRouter;

    private final ThreadPoolTaskExecutor chatExecutor;

    private final ThreadPoolTaskScheduler timeoutScheduler;

    private final AiProperties properties;

    /**
     * @param modelRouter     选模型的策略（本轮唯一实现：按用户传入的 {@code modelType}）
     * @param chatExecutor    流式调用的专用线程池（Bean 名见 {@link AsyncConfig#CHAT_EXECUTOR_BEAN_NAME}；
     *                        用 {@code @Qualifier} 点名而不是按类型注入：本项目将来出现第二个执行器时，
     *                        按类型注入会变成"启动期才发现有两个候选"）
     * @param timeoutScheduler 软上限定时器（Bean 名见
     *                        {@link AsyncConfig#CHAT_TIMEOUT_SCHEDULER_BEAN_NAME}）
     * @param properties      网关配置（超时三兄弟都在这里）
     */
    public ChatServiceImpl(ModelRouter modelRouter,
                           @Qualifier(AsyncConfig.CHAT_EXECUTOR_BEAN_NAME) ThreadPoolTaskExecutor chatExecutor,
                           @Qualifier(AsyncConfig.CHAT_TIMEOUT_SCHEDULER_BEAN_NAME) ThreadPoolTaskScheduler timeoutScheduler,
                           AiProperties properties) {
        this.modelRouter = modelRouter;
        this.chatExecutor = chatExecutor;
        this.timeoutScheduler = timeoutScheduler;
        this.properties = properties;
        warnIfSoftTimeoutUnschedulable();
    }

    @Override
    public SseEmitter stream(ChatRequest request, CallerContext caller) {
        // ① 路由。刻意留在**请求线程**上：未知 modelType（10200）在这里抛出，还能走正常 Result 出口；
        //    emitter 一旦交给容器，同样的失败就只能写成 error 帧了（设计 §3.3）
        ModelRouter.Decision decision = modelRouter.route(request);

        // 两条链路日志（设计 §4.5）。只打条数与模型，**不打消息正文**
        //（正文属用户数据，且可能很长 —— 与 ChatMessage 不输出 content 是同一条纪律）
        log.info("[chat] 开始 stream: userId={} tenantId={} modelType={} messages={}",
                caller.userId(), caller.tenantId(), request.getModelType(),
                request.getMessages() == null ? 0 : request.getMessages().size());
        log.info("[chat] 路由结果: provider={} model={} servedBy={}",
                decision.descriptor().type().getProviderKey(), decision.descriptor().modelName(),
                decision.servedBy().getWireValue());

        // 硬超时显式传进去，不依赖 spring.mvc.async.request-timeout 这个全局默认值兜底
        //（两处同源，避免"配了个值却不生效"）
        SseEmitter emitter = createEmitter(properties.getStreamTimeoutMs());
        ChatStreamSession session = new ChatStreamSession(emitter, decision);

        // 断开的**补充**信号（主判据是工作线程里写帧失败，见 ChatStreamSession）。
        // 三者都只做静默取消：onCompletion 在**正常收尾**时同样会触发，
        // 在那里记"客户端断开"会凭空造出假告警
        emitter.onCompletion(session::cancelUpstream);
        emitter.onError(throwable -> {
            log.debug("[chat] emitter onError 回调（连接已不可用）：{}", throwable.getClass().getSimpleName());
            session.cancelUpstream();
        });
        emitter.onTimeout(session::cancelUpstream);

        // ② 提交到专用线程池。池满时 TaskRejectedException 直接向上抛（不在这里 catch）：
        //    此刻尚未开流，由 GlobalExceptionHandler 出 HTTP 503 + 20100；在那里记日志是因为
        //    异常的 message 自带线程池现场（pool size / active threads / queued tasks），
        //    比在这里补一句"池满了"信息量大得多
        chatExecutor.execute(() -> runStream(request, caller, decision, session));

        // ③ 立刻返回：响应头在这里发出，此后一切失败都只能写帧
        return emitter;
    }

    /**
     * 工作线程上的全部逻辑。
     *
     * @param request  请求体
     * @param caller   调用方身份快照（请求线程上抓取）
     * @param decision 路由结果
     * @param session  本次流的会话状态
     */
    private void runStream(ChatRequest request, CallerContext caller,
                           ModelRouter.Decision decision, ChatStreamSession session) {
        // ★ D10：工作线程上没有登录态（TenantContext 是纯 ThreadLocal，过滤器在 finally 里 clear 过），
        //   这里重建一份与请求线程等价的上下文。本阶段聊天链路不读库，所以少了这行也看不出差别 ——
        //   但阶段3 的 RAG 一旦在这里查库，缺上下文会表现成一个"没有租户上下文"的 500
        TenantContext.set(caller.userId(), caller.tenantId(), caller.tokenId());
        ScheduledFuture<?> softTimeout = null;
        try {
            softTimeout = scheduleSoftTimeout(session);
            decision.service().stream(request.getMessages(), decision.descriptor(),
                    session::onChunk, session.cancelToken());
            // 走到这里说明上游正常结束（也可能是被取消后 provider 静默返回：
            // 那种情况下 session 里已经记着"客户端已断开"，finishDone 不会再写帧）
            session.finishDone();
        } catch (BusinessException ex) {
            // 上游不可达 / 超时 / 中途报错：转成 error 帧，**不向上抛** ——
            // 响应头早已发出，抛出去也没有出口（AiModelService 的类注释已把这条写进契约）
            session.finishError(ex.getCode(), ex.getMessage());
        } catch (RuntimeException ex) {
            // 剩下的都是程序缺陷：打堆栈 + 回 50000，与"上游真的挂了"在日志里分开
            log.error("[chat] 流处理出现未预期异常: provider={}",
                    decision.descriptor().type().getProviderKey(), ex);
            session.finishError(ResultCode.SYSTEM_ERROR.getCode(), ResultCode.SYSTEM_ERROR.getMsg());
        } finally {
            if (softTimeout != null) {
                // 流已收尾，定时任务没有存在的意义（false = 已在执行就别打断它）
                softTimeout.cancel(false);
            }
            // 兜底收尾：任何路径都必须关掉 emitter，否则前端会一直停在"生成中"
            session.completeIfNeeded();
            // 与过滤器同一条纪律：池线程不能带着上一个请求的上下文（TenantContext 类注释）
            TenantContext.clear();
        }
    }

    /**
     * 安排本流的软上限定时任务。
     *
     * <p>软上限与 emitter 硬超时是两个机制（设计 §4.4）：硬超时到点是"连接直接断，发不出任何帧"，
     * 而软上限是我们自己主动发一帧 {@code done} + {@code finishReason=timeout}。
     * 两者必须有间隔（默认 290s / 300s），否则软上限永远来不及发。
     *
     * @param session 本次流的会话状态
     * @return 定时任务句柄；本流的软上限被关闭时返回 {@code null}
     */
    private ScheduledFuture<?> scheduleSoftTimeout(ChatStreamSession session) {
        long softTimeoutMs = properties.getSoftTimeoutMs();
        if (softTimeoutMs <= 0) {
            // 0 / 负数 = 关闭软上限（合法性已在构造期 warn 过）。刻意不在这里把它纠正成一个"合理值"：
            // 配置说什么就是什么，静默改成别的数字只会让"配置没生效"再次发生
            return null;
        }
        return timeoutScheduler.schedule(session::finishTimeout, Instant.now().plusMillis(softTimeoutMs));
    }

    /**
     * 创建本次流的 {@link SseEmitter}。
     *
     * <p><b>抽成独立方法只为一件事：让单测换掉它。</b>
     * {@code SseEmitter} 的 {@code initialize(Handler)} 是包内可见的（测试与它不同包，够不着），
     * 而未初始化的 emitter 既不能记录也不能断言任何帧 —— 可"meta 只发一次 / delta 序列 /
     * done 收尾 / 上游失败改发 error 帧"恰恰是这条链路最该被钉住的契约。
     * 生产路径永远是下面这一行；本方法<b>不是</b>给外部扩展用的。
     *
     * @param timeoutMs emitter 硬超时（毫秒）
     * @return SSE 出口
     */
    SseEmitter createEmitter(long timeoutMs) {
        return new SseEmitter(timeoutMs);
    }

    /**
     * 启动期体检：软上限与硬超时的量级关系。
     *
     * <p>为什么只 warn 不 fail-fast：这两个键是运维旋钮（演示时关掉软上限是合法选择），
     * 配错的表现是"超长的流在硬超时处被直接掐断、客户端收不到终止帧"—— 一个排查起来
     * 很费时间的现象，而这里一句话就能把它指出来。
     */
    private void warnIfSoftTimeoutUnschedulable() {
        long softTimeoutMs = properties.getSoftTimeoutMs();
        long streamTimeoutMs = properties.getStreamTimeoutMs();
        if (softTimeoutMs <= 0) {
            log.warn("nexus.ai.soft-timeout-ms={} —— 服务端软上限已关闭：超长的流只能在硬超时（{}ms）处"
                    + "被直接掐断，客户端收不到 done 帧", softTimeoutMs, streamTimeoutMs);
            return;
        }
        if (softTimeoutMs >= streamTimeoutMs) {
            log.warn("nexus.ai.soft-timeout-ms({}) 不小于 stream-timeout-ms({}) —— 软上限永远来不及"
                    + "发出 done 帧（硬超时一到，一个字节都写不出去），必须留出间隔（如 290s / 300s）",
                    softTimeoutMs, streamTimeoutMs);
        }
    }
}
