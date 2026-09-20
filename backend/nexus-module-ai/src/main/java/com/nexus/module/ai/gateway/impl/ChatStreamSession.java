package com.nexus.module.ai.gateway.impl;

import com.nexus.common.result.Result;
import com.nexus.module.ai.gateway.AiModelService.CancelToken;
import com.nexus.module.ai.gateway.AiModelService.Chunk;
import com.nexus.module.ai.gateway.ModelDescriptor;
import com.nexus.module.ai.gateway.ModelRouter;
import com.nexus.module.ai.gateway.dto.ChatStreamDelta;
import com.nexus.module.ai.gateway.dto.ChatStreamDone;
import com.nexus.module.ai.gateway.dto.ChatStreamError;
import com.nexus.module.ai.gateway.dto.ChatStreamMeta;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;

/**
 * 一次流式对话的会话状态：帧的**唯一出口**、终态的唯一性、取消与收尾。
 *
 * <h2>为什么把它从 ChatService 里拆出来</h2>
 * 编排（选谁、什么时候提交、异常怎么分类）与"这条流现在处于什么状态"是两件事。
 * 后者要被三个地方同时触碰 —— 工作线程（发 delta）、软上限定时器（发 done）、
 * 回调线程（客户端断开时取消）—— 把它们收进一个对象、用同一把锁串起来，
 * 才能让下面三条不变量在代码里一眼可查：
 * <ol>
 *     <li><b>{@code meta} 有且仅有一帧，且先于任何 {@code delta}</b>（契约时序规则 1）；</li>
 *     <li><b>终止帧唯一</b>：{@code done} xor {@code error}，发完立即 {@code complete()}（规则 2）；</li>
 *     <li><b>客户端断开之后一帧都不再写</b>，且立刻取消上游（设计 §4.4）。</li>
 * </ol>
 *
 * <h2>客户端断开是怎么被发现的</h2>
 * 主判据是"写帧抛异常"：客户端一断，下一次 {@code emitter.send(...)} 必然失败
 * （{@code IOException}，Spring 6.1 的 {@code AsyncRequestNotUsableException} 也是它的子类）。
 * 这是<b>必然发生且最可靠</b>的信号 —— 只注册回调而不处理它，会出现"curl 已 Ctrl-C、后端既没日志
 * 也没关上游"（验收 2.3-2 直接不过，上游还在继续烧算力）。
 *
 * <p>本类<b>不</b>向上抛任何异常：它被 {@code AiModelService} 的 {@code onChunk} 回调间接调用，
 * 而那份契约明确要求"回调不得抛异常"（见 {@code AiModelService} 类注释）。写帧失败被翻译成
 * "置位取消 + 记一条日志 + 返回 false"，由调用方自行决定是否继续。
 *
 * <h2>线程安全</h2>
 * 所有改动状态的方法都是 {@code synchronized}：写帧可能来自工作线程（delta/done/error）
 * 或软上限定时器线程（timeout）。{@link #onChunk} / {@link #cancelUpstream} / {@link #completeIfNeeded}
 * 在取消竞争下都必须是幂等的。
 *
 * @author nexus
 */
final class ChatStreamSession {

    private static final Logger log = LoggerFactory.getLogger(ChatStreamSession.class);

    /** 帧名（契约 §6.2：{@code event:} 后的取值，逐字一致）。 */
    private static final String FRAME_META = "meta";

    private static final String FRAME_DELTA = "delta";

    private static final String FRAME_DONE = "done";

    private static final String FRAME_ERROR = "error";

    private final SseEmitter emitter;

    private final ModelRouter.Decision decision;

    private final CancelToken cancelToken = new CancelToken();

    /** 会话创建时刻（请求线程上、开流前）—— {@code done.durationMs} 的起点。 */
    private final long startedAtMillis;

    /** 已成功写出的 {@code delta} 帧数（{@code done.deltaCount} / {@code error.deltaCount} 的来源）。 */
    private int deltaCount;

    /** {@code meta} 是否已发出（保证只发一次）。 */
    private boolean metaSent;

    /** 是否已发过终止帧（{@code done} / {@code error} 二选一）。 */
    private boolean terminalSent;

    /** 是否已调用过 {@code emitter.complete()}。 */
    private boolean completed;

    /** 客户端是否已断开（本对象据此拒绝一切后续写帧）。 */
    private boolean clientGone;

    /** 上游给出的结束原因（由 {@code Chunk.finished} 带来）；未给出时为 {@code null}。 */
    private String upstreamFinishReason;

    /**
     * @param emitter  本次响应的 SSE 出口
     * @param decision 路由结果（{@code meta} 帧与日志都取自它）
     */
    ChatStreamSession(SseEmitter emitter, ModelRouter.Decision decision) {
        this.emitter = emitter;
        this.decision = decision;
        this.startedAtMillis = System.currentTimeMillis();
    }

    /**
     * 取消信号（交给 provider 保管上游资源）。
     *
     * @return 本次流的取消信号
     */
    CancelToken cancelToken() {
        return cancelToken;
    }

    /**
     * 取消上游调用，<b>静默</b>（不记日志）。
     *
     * <p>为什么静默：它被 {@code emitter} 的 {@code onCompletion} 回调调用，而那回调在
     * <b>正常收尾</b>时同样会触发 —— 在那里记"客户端断开"会凭空造出一条假告警，
     * 正中设计 §4.4 想避免的那类噪音。真正的断开有它自己的日志（见写帧失败处）。
     */
    synchronized void cancelUpstream() {
        cancelToken.cancel();
    }

    /**
     * 上游分片到达：按需先发 {@code meta}，再把文本片作为 {@code delta} 写出。
     *
     * <p>它是 {@code AiModelService#stream} 的 {@code onChunk} 实现，<b>绝不抛异常</b>。
     *
     * @param chunk 上游分片
     */
    synchronized void onChunk(Chunk chunk) {
        if (terminalSent || clientGone) {
            // 流已收尾（或客户端已走）：取消之后上游往往还会再吐几拍，一律丢弃
            return;
        }
        String content = chunk.content();
        if (content != null && !content.isEmpty()) {
            // ★ meta 卡在第一个 delta 之前（时序规则 1）：这里是"首次取到上游内容"的唯一入口，
            //   所以 meta 的发出点就放在这里 —— 上游连接慢时客户端也就不必干等
            sendMetaIfUnsent();
            if (send(FRAME_DELTA, Result.success(new ChatStreamDelta(content)))) {
                deltaCount++;
            }
        }
        if (chunk.finishReason() != null) {
            upstreamFinishReason = chunk.finishReason();
        }
    }

    /**
     * 正常结束：发 {@code done} 帧，结束原因取上游给过的那个（没给过就是 {@code stop}）。
     */
    synchronized void finishDone() {
        finishDone(upstreamFinishReason == null ? ChatStreamDone.FINISH_STOP : upstreamFinishReason);
    }

    /**
     * 服务端软上限到点：主动收尾，而不是让 emitter 硬超时把连接直接掐掉。
     *
     * <p>顺序是刻意的 —— 先取消（关上游流，让它立刻停止生成、也把阻塞在 read 上的工作线程放出来），
     * 再写 {@code done}：硬超时到点后一个字节都写不出去，所以本方法必须早于硬超时触发
     * （{@code nexus.ai.soft-timeout-ms} &lt; {@code stream-timeout-ms}，见 {@code AiProperties}）。
     */
    synchronized void finishTimeout() {
        cancelToken.cancel();
        finishDone(ChatStreamDone.FINISH_TIMEOUT);
    }

    /**
     * 异常结束：发 {@code error} 帧并收尾。
     *
     * <p>★ {@code data} 必须是一个对象（{@code {"deltaCount": N}}）而不是 {@code null} ——
     * 契约靠它让前端知道"已经吐了多少字"，据此决定保留还是回滚本轮。所以这里走
     * {@code Result.failure(code, msg, payload)} 而不是只有两个参数的 {@code failure(code, msg)}。
     *
     * <p>刻意<b>不</b>在这一路径补发 {@code meta}：{@code meta} 的语义是"这次用了哪个模型"，
     * 而上游一个字都没吐出来时并无"用过的模型"可言（成功路径才需要保证 meta 一定有一帧，
     * 见 {@link #finishDone(String)}）。
     *
     * @param code 业务码（20100 上游不可用 / 50000 未预期异常）
     * @param msg  可展示给前端的文案
     */
    synchronized void finishError(int code, String msg) {
        if (terminalSent) {
            return;
        }
        terminalSent = true;
        if (clientGone) {
            // 客户端已经走了，帧发不出去也没有意义（"客户端断开"那条日志已经记过，不再补记）
            completeIfNeeded();
            return;
        }
        if (send(FRAME_ERROR, Result.failure(code, msg, new ChatStreamError(deltaCount)))) {
            log.warn("[chat] 流异常结束: provider={} deltaCount={} durationMs={} code={} msg={}",
                    providerKey(), deltaCount, durationMillis(), code, msg);
        }
        completeIfNeeded();
    }

    /**
     * 兜底收尾：<b>任何</b>路径的最后都要把 emitter 关掉。
     *
     * <p>前端只认三个出口（{@code done} / {@code error} / EOF），但"我们这边没关"会让连接
     * 一直挂着、前端停在"生成中" —— 所以这一步不能只挂在正常路径上。
     */
    synchronized void completeIfNeeded() {
        if (completed) {
            return;
        }
        completed = true;
        try {
            emitter.complete();
        } catch (RuntimeException ex) {
            // 收尾路径上的异常不再抛：连接本来就要废弃，抛出去只会盖掉真正的原因
            log.debug("emitter.complete() 失败（可忽略）：{}", ex.getMessage());
        }
    }

    /**
     * 发 {@code done} 帧并收尾。
     *
     * @param finishReason {@code stop} / {@code length} / {@code timeout}
     */
    private synchronized void finishDone(String finishReason) {
        if (terminalSent) {
            return;
        }
        terminalSent = true;
        if (clientGone) {
            completeIfNeeded();
            return;
        }
        // 成功路径必须保证 meta 有一帧：上游一个字都没吐就正常结束（或软上限恰好在首片之前到点）时，
        // 这里是它唯一的发出点 —— 契约说的是"仅有且**必有**一帧"，不能只有前者成立
        sendMetaIfUnsent();
        if (send(FRAME_DONE, Result.success(new ChatStreamDone(finishReason, deltaCount, durationMillis())))) {
            log.info("[chat] 流结束: provider={} deltaCount={} durationMs={} finishReason={}",
                    providerKey(), deltaCount, durationMillis(), finishReason);
        }
        completeIfNeeded();
    }

    /**
     * 发 {@code meta} 帧（若还没发过）。
     *
     * <p>调用方已持锁（本类的公开方法都是 {@code synchronized}）。
     */
    private void sendMetaIfUnsent() {
        if (metaSent) {
            return;
        }
        metaSent = true;
        ModelDescriptor descriptor = decision.descriptor();
        send(FRAME_META, Result.success(new ChatStreamMeta(
                descriptor.type(), descriptor.modelName(), decision.servedBy().getWireValue())));
    }

    /**
     * 写一帧：<b>本类所有异常处置的唯一入口</b>。
     *
     * @param frameName 事件名（{@code meta} / {@code delta} / {@code done} / {@code error}）
     * @param payload   该帧的 {@code data}（{@code Result<T>}，契约决策 D4）
     * @return {@code true} 表示写成功；{@code false} 表示这条流已经废了（客户端断开或已收尾）
     */
    private boolean send(String frameName, Result<?> payload) {
        if (clientGone) {
            return false;
        }
        try {
            emitter.send(SseEmitter.event().name(frameName).data(payload));
            return true;
        } catch (IOException ex) {
            // ★ 客户端断开的主判据（设计 §4.4）。AsyncRequestNotUsableException 也是 IOException 的子类
            markClientGone();
            log.info("[chat] 客户端断开，已取消上游调用: provider={} deltaCount={} durationMs={}",
                    providerKey(), deltaCount, durationMillis());
            return false;
        } catch (IllegalStateException ex) {
            // 框架已把这条 emitter 标记为 complete（典型是 emitter 硬超时到点）：连接同样已经废了。
            // 与上面分开记 —— 它是服务端侧的收尾，不是"客户端断开"；混在一起会让
            // 排查"为什么流断了"的人看到一条指向错误方向的线索
            markClientGone();
            log.warn("[chat] 写帧失败（emitter 已不可用）: provider={} deltaCount={} cause={}",
                    providerKey(), deltaCount, ex.getMessage());
            return false;
        }
    }

    /**
     * 记下"客户端已走"<b>并取消上游</b>，只做一次。
     *
     * <p>关流而不是只置标志：取消的意义是让<b>上游</b>停止生成，而不只是"我们不再读"
     * （见 {@code CancelToken} 类注释）。只记一次是因为断开之后的每一次写帧都会失败，
     * 不设门槛就会刷出一屏重复日志。
     */
    private void markClientGone() {
        if (clientGone) {
            return;
        }
        clientGone = true;
        cancelToken.cancel();
    }

    private long durationMillis() {
        return System.currentTimeMillis() - startedAtMillis;
    }

    /** 日志用的 provider 标识（{@code ollama} / {@code deepseek}），来自路由结果而非重新判断。 */
    private String providerKey() {
        return decision.descriptor().type().getProviderKey();
    }
}
