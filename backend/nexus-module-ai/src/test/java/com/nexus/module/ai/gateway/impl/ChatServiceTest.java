package com.nexus.module.ai.gateway.impl;

import com.nexus.common.exception.BusinessException;
import com.nexus.common.result.Result;
import com.nexus.common.result.ResultCode;
import com.nexus.infrastructure.tenant.TenantContext;
import com.nexus.module.ai.gateway.AiModelService;
import com.nexus.module.ai.gateway.AiModelService.CancelToken;
import com.nexus.module.ai.gateway.AiModelService.Chunk;
import com.nexus.module.ai.gateway.ChatService;
import com.nexus.module.ai.gateway.ModelDescriptor;
import com.nexus.module.ai.gateway.ModelRouter;
import com.nexus.module.ai.gateway.ModelType;
import com.nexus.module.ai.gateway.config.AiProperties;
import com.nexus.module.ai.gateway.dto.ChatMessage;
import com.nexus.module.ai.gateway.dto.ChatRequest;
import com.nexus.module.ai.gateway.dto.ChatStreamDelta;
import com.nexus.module.ai.gateway.dto.ChatStreamDone;
import com.nexus.module.ai.gateway.dto.ChatStreamError;
import com.nexus.module.ai.gateway.dto.ChatStreamMeta;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ChatServiceImpl} 单元测试 —— 编排层的四条不变量（契约 §6.2 的时序规则）。
 *
 * <p>覆盖的正是"改坏了没人看得出来"的那几处：
 * <ol>
 *     <li>正常流：{@code meta} 只发一次且在首个 {@code delta} 之前、{@code delta} 原样、{@code done} 收尾；</li>
 *     <li>上游不可达：发 {@code error} 帧（20100 + {@code {deltaCount}} 对象）<b>而不是抛异常</b>
 *         —— 决策 D7 的直接判据（"失败即如实报错，不做降级"）；</li>
 *     <li>客户端断开：写帧失败后立刻取消上游、不再写任何帧，且 emitter 仍被关闭（验收 2.3-2 的单测代理）；</li>
 *     <li>服务端软上限：到点发 {@code done(timeout)} 而不是等硬超时把连接掐断；</li>
 *     <li>线程池满：{@code TaskRejectedException} 上抛（由全局异常处理器转 503 + 20100）。</li>
 * </ol>
 *
 * <h2>为什么把 emitter 换成记录型实现</h2>
 * {@code SseEmitter} 的 {@code initialize(Handler)} 是包内可见的（本测试与它不同包，够不着），
 * 未初始化的 emitter 既不能记录也不能断言任何帧。于是 {@code ChatServiceImpl} 把"创建 emitter"
 * 抽成了一个包内可见的方法（见其 {@code createEmitter} 的注释），本测试覆写它注入
 * {@link RecordingSseEmitter} —— 断言的是<b>真正发出去的那几帧</b>，而不是"某个内部方法被调用过"。
 *
 * <p>线程池与定时器都用真实的实现（只把并发度调小、把超时压短）：mock 掉它们就等于把
 * "异步编排"这件事本身从被测范围里摘出去了。
 *
 * @author nexus
 */
class ChatServiceTest {

    private ThreadPoolTaskExecutor executor;

    private ThreadPoolTaskScheduler scheduler;

    private AiProperties properties;

    /** 本次用例开出来的所有 emitter（正常情况只有一条流）。 */
    private final List<RecordingSseEmitter> emitters = new CopyOnWriteArrayList<>();

    /** 「线程池已满」用例里用来卡住唯一线程的闸门。 */
    private CountDownLatch blockingLatch;

    @BeforeEach
    void setUp() {
        executor = newExecutor(4);
        scheduler = newScheduler();
        properties = new AiProperties();
    }

    @AfterEach
    void tearDown() {
        // 兜底放行：万一某条用例中途失败，卡在 provider 里的池线程会让构建不退出
        if (blockingLatch != null) {
            blockingLatch.countDown();
        }
        executor.shutdown();
        scheduler.destroy();
        TenantContext.clear();
    }

    @Test
    @DisplayName("正常流：meta 只发一次且在首个 delta 之前，delta 序列原样，done 收尾并 complete")
    void shouldEmitMetaThenDeltasThenDone() throws Exception {
        FakeAiModelService provider = FakeAiModelService.emitting(List.of(
                Chunk.text("你"),
                Chunk.text("好"),
                Chunk.finished(ChatStreamDone.FINISH_STOP)), 0L);

        newService(provider, 0).stream(request("OLLAMA"), caller());

        RecordingSseEmitter emitter = onlyEmitter();
        assertTrue(emitter.awaitCompletion(2_000L), "流没有在 2 秒内收尾");
        assertEquals(List.of("meta", "delta", "delta", "done"), emitter.eventNames());

        List<SentFrame> frames = emitter.frames();
        assertEquals(ResultCode.SUCCESS.getCode(), frames.get(0).payload().getCode());

        ChatStreamMeta meta = payloadOf(frames.get(0), ChatStreamMeta.class);
        assertEquals(ModelType.OLLAMA, meta.modelType());
        assertEquals("qwen2.5:7b", meta.model());
        assertEquals(ModelRouter.ServedBy.USER_SELECTED.getWireValue(), meta.servedBy());

        assertEquals("你", payloadOf(frames.get(1), ChatStreamDelta.class).content());
        assertEquals("好", payloadOf(frames.get(2), ChatStreamDelta.class).content());

        ChatStreamDone done = payloadOf(frames.get(3), ChatStreamDone.class);
        assertEquals(ChatStreamDone.FINISH_STOP, done.finishReason());
        assertEquals(2, done.deltaCount());
        assertTrue(done.durationMs() >= 0L, "耗时不该是负数");
        assertTrue(emitter.isCompleted(), "发完终止帧必须 complete");
    }

    @Test
    @DisplayName("上游不可达：发 error 帧（20100，data 为 {deltaCount} 对象），不向上抛异常")
    void shouldEmitErrorFrameWhenUpstreamIsUnavailable() throws Exception {
        FakeAiModelService provider = FakeAiModelService.failing(
                new BusinessException(ResultCode.CHAT_UPSTREAM_UNAVAILABLE));

        assertDoesNotThrow(() -> newService(provider, 0).stream(request("OLLAMA"), caller()));

        RecordingSseEmitter emitter = onlyEmitter();
        assertTrue(emitter.awaitCompletion(2_000L), "流没有在 2 秒内收尾");
        // 一个字都没吐出来：不该有 meta、也不该有 delta（meta 的语义是"这次用了哪个模型"，
        // 而上游根本没给出响应）
        assertEquals(List.of("error"), emitter.eventNames());

        SentFrame errorFrame = emitter.frames().get(0);
        assertEquals(ResultCode.CHAT_UPSTREAM_UNAVAILABLE.getCode(), errorFrame.payload().getCode());
        assertEquals(ResultCode.CHAT_UPSTREAM_UNAVAILABLE.getMsg(), errorFrame.payload().getMsg());
        // ★ data 必须是对象而不是 null：契约靠它让前端知道"已经吐了多少字"
        assertEquals(0, payloadOf(errorFrame, ChatStreamError.class).deltaCount());
        assertTrue(emitter.isCompleted());
    }

    @Test
    @DisplayName("客户端断开：写帧失败后立刻取消上游、不再写任何帧，且 emitter 仍被关闭")
    void shouldCancelUpstreamAndStopWritingWhenClientDisconnects() throws Exception {
        FakeAiModelService provider = FakeAiModelService.emitting(List.of(
                Chunk.text("你"),
                Chunk.text("好"),
                Chunk.text("！"),
                Chunk.finished(ChatStreamDone.FINISH_STOP)), 0L);
        // 第 3 次写帧起失败：前两次是 meta 与第一个 delta
        ChatService service = newService(provider, 3);

        assertDoesNotThrow(() -> service.stream(request("OLLAMA"), caller()));

        RecordingSseEmitter emitter = onlyEmitter();
        assertTrue(emitter.awaitCompletion(2_000L), "断开后仍然要把 emitter 关掉");
        assertEquals(List.of("meta", "delta"), emitter.eventNames(), "断开之后不该再写出任何帧");
        assertTrue(provider.cancelToken().isCancelled(), "断开后必须取消上游（关流即断上游）");
        assertTrue(emitter.isCompleted());
    }

    @Test
    @DisplayName("服务端软上限：到点发 done(timeout) 并取消上游，不让硬超时把连接掐掉")
    void shouldFinishWithTimeoutWhenSoftLimitIsReached() throws Exception {
        // 软上限压到 80ms，而 provider 要吐 10 片 × 50ms ≈ 500ms —— 软上限必然先到
        properties.setSoftTimeoutMs(80L);
        FakeAiModelService provider = FakeAiModelService.emitting(slowChunks(10), 50L);

        newService(provider, 0).stream(request("OLLAMA"), caller());

        RecordingSseEmitter emitter = onlyEmitter();
        assertTrue(emitter.awaitCompletion(2_000L), "软上限到点后应主动收尾");
        List<SentFrame> frames = emitter.frames();
        List<String> eventNames = emitter.eventNames();
        assertEquals("done", eventNames.get(eventNames.size() - 1), "终止帧必须是 done");

        ChatStreamDone done = payloadOf(frames.get(frames.size() - 1), ChatStreamDone.class);
        assertEquals(ChatStreamDone.FINISH_TIMEOUT, done.finishReason());
        assertTrue(done.deltaCount() >= 1, "截断前至少已经吐出一片正文");
        assertTrue(provider.cancelToken().isCancelled(), "软上限截断同样要取消上游");
        assertTrue(emitter.isCompleted());
    }

    @Test
    @DisplayName("线程池已满：TaskRejectedException 上抛（由 GlobalExceptionHandler 转成 503 + 20100）")
    void shouldRejectWhenPoolIsFull() {
        // 单线程池 + 队列 0：第一条流占住唯一的线程，第二条必然被拒（设计 D8：不排队）
        executor.shutdown();
        executor = newExecutor(1);
        blockingLatch = new CountDownLatch(1);
        FakeAiModelService provider = FakeAiModelService.blocking(blockingLatch);
        ChatService service = newService(provider, 0);

        service.stream(request("OLLAMA"), caller());

        assertThrows(TaskRejectedException.class, () -> service.stream(request("OLLAMA"), caller()));
    }

    @Test
    @DisplayName("D10：工作线程上重建了请求线程的登录态（userId / tenantId / tokenId）")
    void shouldPropagateCallerContextToWorkerThread() throws Exception {
        FakeAiModelService provider = FakeAiModelService.emitting(
                List.of(Chunk.finished(ChatStreamDone.FINISH_STOP)), 0L);

        newService(provider, 0).stream(request("OLLAMA"), new ChatService.CallerContext(7L, 70L, "jti-7"));

        assertTrue(onlyEmitter().awaitCompletion(2_000L));
        // 断言的是"工作线程上读到的值"：TenantContext 是纯 ThreadLocal、过滤器在 finally 里 clear 过，
        // 不显式透传的话这三个值在池线程里全是 null（阶段3 的 RAG 会因此直接抛异常）
        assertEquals(7L, provider.seenUserId());
        assertEquals(70L, provider.seenTenantId());
        assertEquals("jti-7", provider.seenTokenId());
    }

    /**
     * 构造被测服务，并把 emitter 换成记录型实现（见类注释）。
     *
     * @param provider        伪造的 provider
     * @param failFromAttempt 第几次写帧起抛 IOException（1 起算；0 = 永不失败）
     * @return 被测服务（匿名子类，仅覆写 {@code createEmitter}）
     */
    private ChatServiceImpl newService(AiModelService provider, int failFromAttempt) {
        ModelRouter router = request -> new ModelRouter.Decision(
                provider, provider.descriptor(), ModelRouter.ServedBy.USER_SELECTED);

        return new ChatServiceImpl(router, executor, scheduler, properties) {

            @Override
            SseEmitter createEmitter(long timeoutMs) {
                RecordingSseEmitter emitter = new RecordingSseEmitter(failFromAttempt);
                emitters.add(emitter);
                return emitter;
            }
        };
    }

    private RecordingSseEmitter onlyEmitter() {
        assertEquals(1, emitters.size(), "本用例只应开出一条流");
        return emitters.get(0);
    }

    private static ChatRequest request(String modelType) {
        ChatMessage message = new ChatMessage();
        message.setRole(ChatMessage.ROLE_USER);
        message.setContent("用三句话介绍杭州");

        ChatRequest request = new ChatRequest();
        request.setMessages(List.of(message));
        request.setModelType(modelType);
        return request;
    }

    private static ChatService.CallerContext caller() {
        return new ChatService.CallerContext(1L, 10L, "jti-1");
    }

    private static List<Chunk> slowChunks(int count) {
        List<Chunk> chunks = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            chunks.add(Chunk.text("第" + i + "片"));
        }
        return chunks;
    }

    private static <T> T payloadOf(SentFrame frame, Class<T> payloadType) {
        return assertInstanceOf(payloadType, frame.payload().getData(),
                "帧 " + frame.event() + " 的 data 类型不符合契约");
    }

    private static ThreadPoolTaskExecutor newExecutor(int poolSize) {
        ThreadPoolTaskExecutor taskExecutor = new ThreadPoolTaskExecutor();
        taskExecutor.setCorePoolSize(poolSize);
        taskExecutor.setMaxPoolSize(poolSize);
        taskExecutor.setQueueCapacity(0);
        taskExecutor.setThreadNamePrefix("test-chat-");
        taskExecutor.initialize();
        return taskExecutor;
    }

    private static ThreadPoolTaskScheduler newScheduler() {
        ThreadPoolTaskScheduler taskScheduler = new ThreadPoolTaskScheduler();
        taskScheduler.setPoolSize(1);
        taskScheduler.setThreadNamePrefix("test-chat-timeout-");
        taskScheduler.initialize();
        return taskScheduler;
    }

    /**
     * 一帧：事件名 + 解包后的 {@code Result}。
     *
     * @param event   事件名（{@code meta} / {@code delta} / {@code done} / {@code error}）
     * @param payload 该帧的完整 {@code Result}
     * @author nexus
     */
    private record SentFrame(String event, Result<?> payload) {
    }

    /**
     * 记录型 {@link SseEmitter}：把发出去的帧按顺序收下来，供断言使用。
     *
     * <h2>为什么覆写 {@code send(SseEventBuilder)} 就够了</h2>
     * 生产代码写帧只有一条路径：{@code emitter.send(SseEmitter.event().name(X).data(Y))}。
     * 而 {@code SseEventBuilder} 实现里已经把"协议文本"与"载荷对象"分好了 ——
     * {@code build()} 的产物中，String 那些是 {@code "event:delta\ndata:"} 与 {@code "\n\n"} 之类的协议文本，
     * 剩下的就是我们的载荷对象（{@code Result<T>}）。本类据此把每一帧还原成
     * （事件名，载荷）二元组，不碰任何框架内部实现，也不依赖 Spring 的写帧顺序。
     *
     * <p>{@code failFromAttempt} 用来模拟"客户端断开"：从第 N 次写帧起抛 IOException
     * —— 这正是 {@code SseEmitter} 在真实断开时的行为（{@code AsyncRequestNotUsableException}
     * 也是 IOException 的子类）。
     *
     * @author nexus
     */
    private static final class RecordingSseEmitter extends SseEmitter {

        /** SSE 的事件名行前缀（Spring 的 {@code SseEventBuilderImpl} 写的就是它）。 */
        private static final String EVENT_FIELD_PREFIX = "event:";

        private final List<SentFrame> frames = new CopyOnWriteArrayList<>();

        private final CountDownLatch completion = new CountDownLatch(1);

        private final AtomicInteger sendAttempts = new AtomicInteger();

        private final int failFromAttempt;

        private volatile boolean completed;

        RecordingSseEmitter(int failFromAttempt) {
            super(60_000L);
            this.failFromAttempt = failFromAttempt;
        }

        @Override
        public void send(SseEventBuilder builder) throws IOException {
            if (failFromAttempt > 0 && sendAttempts.incrementAndGet() >= failFromAttempt) {
                throw new IOException("Broken pipe（模拟客户端断开）");
            }
            String eventName = null;
            for (ResponseBodyEmitter.DataWithMediaType item : builder.build()) {
                Object data = item.getData();
                if (data instanceof String text) {
                    eventName = resolveEventName(text, eventName);
                } else if (data instanceof Result<?> payload) {
                    frames.add(new SentFrame(eventName, payload));
                }
            }
        }

        @Override
        public void complete() {
            completed = true;
            completion.countDown();
        }

        List<SentFrame> frames() {
            return frames;
        }

        List<String> eventNames() {
            List<String> names = new ArrayList<>(frames.size());
            for (SentFrame frame : frames) {
                names.add(frame.event());
            }
            return names;
        }

        boolean isCompleted() {
            return completed;
        }

        boolean awaitCompletion(long timeoutMillis) throws InterruptedException {
            return completion.await(timeoutMillis, TimeUnit.MILLISECONDS);
        }

        /**
         * 从协议文本片段里取出事件名。
         *
         * <p>Spring 会把 {@code "event:<名>\n"} 与随后的 {@code "data:"} 合成同一个文本片段
         * （{@code SseEventBuilderImpl.append} 累积到下一次 {@code saveAppendedText}），
         * 所以这里按「找到 {@code event:} 行、截到该行行尾」来取，两种拼法都能取到。
         *
         * @param text      文本片段
         * @param current   当前已知的事件名（本片段没有事件名行时原样返回）
         * @return 事件名
         */
        private static String resolveEventName(String text, String current) {
            int eventAt = text.indexOf(EVENT_FIELD_PREFIX);
            if (eventAt < 0) {
                return current;
            }
            int lineEnd = text.indexOf('\n', eventAt);
            return text.substring(eventAt + EVENT_FIELD_PREFIX.length(),
                    lineEnd < 0 ? text.length() : lineEnd).trim();
        }
    }

    /**
     * 伪造的 provider：按脚本吐分片、按脚本失败、或按脚本阻塞。
     *
     * <p>它同时记录两样东西，供测试断言：
     * ① 拿到的 {@link CancelToken}（验证"断开/软上限后上游被取消"）；
     * ② <b>工作线程上读到的登录态</b>（验证决策 D10 的上下文透传 —— 这是它唯一的可见证据）。
     *
     * <p>吐分片的循环与真实 provider 遵守同一条约定：每轮先看取消标志，取消后<b>静默</b>返回。
     *
     * @author nexus
     */
    private static final class FakeAiModelService implements AiModelService {

        private static final ModelDescriptor DESCRIPTOR = new ModelDescriptor(
                ModelType.OLLAMA, "qwen2.5:7b", true, 32_768, ModelDescriptor.CostTier.FREE);

        private final List<Chunk> chunks;

        private final RuntimeException failure;

        private final long chunkDelayMillis;

        private final CountDownLatch releaseLatch;

        private volatile CancelToken cancelToken;

        private volatile Long seenUserId;

        private volatile Long seenTenantId;

        private volatile String seenTokenId;

        private FakeAiModelService(List<Chunk> chunks, RuntimeException failure,
                                   long chunkDelayMillis, CountDownLatch releaseLatch) {
            this.chunks = chunks;
            this.failure = failure;
            this.chunkDelayMillis = chunkDelayMillis;
            this.releaseLatch = releaseLatch;
        }

        static FakeAiModelService emitting(List<Chunk> chunks, long chunkDelayMillis) {
            return new FakeAiModelService(chunks, null, chunkDelayMillis, null);
        }

        static FakeAiModelService failing(RuntimeException failure) {
            return new FakeAiModelService(List.of(), failure, 0L, null);
        }

        static FakeAiModelService blocking(CountDownLatch releaseLatch) {
            return new FakeAiModelService(List.of(), null, 0L, releaseLatch);
        }

        @Override
        public ModelDescriptor descriptor() {
            return DESCRIPTOR;
        }

        @Override
        public void stream(List<ChatMessage> messages, ModelDescriptor descriptor,
                           Consumer<Chunk> onChunk, CancelToken cancelToken) {
            this.cancelToken = cancelToken;
            this.seenUserId = TenantContext.getUserId();
            this.seenTenantId = TenantContext.getTenantId();
            this.seenTokenId = TenantContext.getTokenId();

            if (failure != null) {
                throw failure;
            }
            if (releaseLatch != null) {
                awaitQuietly(releaseLatch);
                return;
            }
            for (Chunk chunk : chunks) {
                if (cancelToken.isCancelled()) {
                    return;
                }
                sleepQuietly(chunkDelayMillis);
                onChunk.accept(chunk);
            }
        }

        CancelToken cancelToken() {
            return cancelToken;
        }

        Long seenUserId() {
            return seenUserId;
        }

        Long seenTenantId() {
            return seenTenantId;
        }

        String seenTokenId() {
            return seenTokenId;
        }

        private static void sleepQuietly(long millis) {
            if (millis <= 0L) {
                return;
            }
            try {
                Thread.sleep(millis);
            } catch (InterruptedException ex) {
                // 收工前恢复中断标志：池线程被中断说明容器在停机，吞掉它会让后续代码误判线程状态
                Thread.currentThread().interrupt();
            }
        }

        private static void awaitQuietly(CountDownLatch latch) {
            try {
                latch.await();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
