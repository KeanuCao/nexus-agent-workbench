package com.nexus.module.ai.gateway;

import com.nexus.common.exception.BusinessException;
import com.nexus.common.result.ResultCode;
import com.nexus.module.ai.gateway.dto.ChatMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * 模型服务端口（provider port）：<b>怎么调一个模型</b>。
 *
 * <p>与 {@code ModelRouter}（<b>这次该调谁</b>）刻意分开（决策 D5）：分开后，
 * "后端自动选模型"只需新增一个 {@code ModelRouter} 实现，本接口与两个 provider 一行不动。
 *
 * <h2>为什么是回调式签名，而不是返回 {@code Stream<String>}</h2>
 * 返回流会把两个问题变成开放问题：<b>流何时被消费</b>、<b>资源何时关闭</b>。
 * 回调式则把"每片文本到了就交给调用方"写进契约，且"首个分片"这个时刻天然可见
 * —— {@code meta} 帧必须卡在它之后、第一个 {@code delta} 之前（设计 §3.2 时序规则 1）。
 *
 * <h2>失败约定（★ 实现本接口前先读这段）</h2>
 * <ul>
 *     <li><b>上游不可达 / 超时 / 中途报错</b>：抛 {@link BusinessException}，码为
 *         {@link ResultCode#CHAT_UPSTREAM_UNAVAILABLE}（20100）。
 *         调用方（{@code ChatService}）捕获后转成 {@code event: error} 帧，<b>绝不向上抛</b>
 *         —— 那时 HTTP 200 + {@code text/event-stream} 的响应头已经发出，抛出去也没有出口。
 *         用 {@link BusinessException} 而不是自造异常类型：它已经是本项目"带码的、可展示的失败"
 *         的载体（{@code msg} 就是给用户看的"模型服务暂时不可用，请稍后重试"），
 *         自造一个只多一个类而不少一行判断；</li>
 *     <li><b>其余异常</b>（如 NPE）属程序缺陷，不要在这里包装成 20100 —— 让
 *         {@code ChatService} 按 50000 处置，否则"上游真的挂了"与"我们写挂了"在日志里分不开。</li>
 * </ul>
 *
 * <h2>调用方要遵守的一条</h2>
 * {@code onChunk} 由调用方实现。<b>它不得抛异常</b>：写帧失败（客户端已断开）时应当
 * 置位 {@link CancelToken}，provider 在下一轮循环里据此停止读取并关闭上游
 * —— 于是本接口的签名里不出现 {@code IOException}，两个实现都干净。
 *
 * <h2>实现要遵守的对应一条：取消后的读异常不算故障</h2>
 * 下游被 {@code cancel()} 关掉之后，provider 的下一次读通常会抛 {@code IOException}
 * （"Connection reset"/"Stream closed"）。<b>那不是我方故障</b>：
 * 实现必须先看 {@link CancelToken#isCancelled()} —— 为 {@code true} 就<b>静默返回</b>，
 * 不要抛 20100。否则客户端每断开一次，日志里就会多一条"上游不可用"的假告警，
 * 而这正是排查真实故障时最容易带偏方向的那类噪音。
 *
 * @author nexus
 */
public interface AiModelService {

    /**
     * 本实现的能力自述（模型名、是否支持工具调用、上下文长度、成本档位）。
     *
     * <p>注册表启动时按 {@link ModelDescriptor#type()} 索引，故<b>同一个类型不允许出现两份自述</b>
     * （重复即启动失败，见 {@code AiModelRegistry}）。
     *
     * @return 能力元数据，各字段不可为 {@code null}
     */
    ModelDescriptor descriptor();

    /**
     * 以流式方式调用模型：上游每吐出一个分片就回调一次。
     *
     * <p>本方法<b>阻塞</b>直到上游结束、出错或被取消（决策 D8：同步阻塞调用 + 专用有界线程池，
     * 不引 WebFlux）。调用它的是工作线程，不是 Tomcat 请求线程。
     *
     * @param messages    会话历史（全量，最后一条是 {@code role=user}）；只读，实现不得修改
     * @param descriptor  本次使用的模型自述。之所以显式传入而不是内部取 {@link #descriptor()}：
     *                    将来同一 provider 支持多模型时，"这次用哪个"应能由路由指定
     * @param onChunk     分片回调：文本片用 {@link Chunk#text(String)}，结束片用
     *                    {@link Chunk#finished(String)}。实现<b>不得</b>让回调抛异常（见类注释）
     * @param cancelToken 取消信号：实现应在读取循环里轮询 {@link CancelToken#isCancelled()}，
     *                    并把上游的资源（如响应流）{@link CancelToken#bind(Closeable)} 进去，
     *                    以便客户端断开时被立即关闭（关流即断上游，见设计 §4.4）
     * @throws BusinessException 上游不可达 / 超时 / 报错（code = 20100）
     */
    void stream(List<ChatMessage> messages, ModelDescriptor descriptor,
                Consumer<Chunk> onChunk, CancelToken cancelToken);

    /**
     * 上游吐出的一个分片。
     *
     * <p>两个字段的语义刻意贴合上游协议的实际形态：文本片只有 {@code content}，
     * 结束片只有 {@code finishReason}（{@code content} 为空串）。
     *
     * @param content      增量文本片段（<b>不是</b>累积全文）；结束片为空串，非 {@code null}
     * @param finishReason 结束原因，取值 {@code stop} / {@code length}；未结束时为 {@code null}。
     *                     服务端软上限截断产生的 {@code timeout} <b>不</b>由 provider 给出
     *                     —— 那是 {@code ChatService} 自己的定时器触发的
     * @author nexus
     */
    record Chunk(String content, String finishReason) {

        /**
         * 文本片。
         *
         * @param content 增量文本（不得为空串 —— 空串是"上游这一拍没吐字"，直接跳过即可，
         *                不必构造分片）
         * @return 一个只有文本、未结束的分片
         */
        public static Chunk text(String content) {
            return new Chunk(content, null);
        }

        /**
         * 结束片：上游明确告知本次生成结束。
         *
         * @param finishReason {@code stop}（正常结束）/ {@code length}（触达 token 上限）
         * @return 一个无文本、已结束的分片
         */
        public static Chunk finished(String finishReason) {
            return new Chunk("", finishReason);
        }
    }

    /**
     * 取消信号：把"客户端已断开"这一个事实，从写帧的一侧传到读上游的一侧。
     *
     * <p><b>为什么需要它</b>：客户端 {@code Ctrl-C} 或前端点"停止生成"后，服务端唯一的可靠信号
     * 是下一次 {@code emitter.send(...)} 抛异常（设计 §4.4）。此刻我们只做两件事 ——
     * 置位取消标志、关掉上游的响应流。<b>关流是"上游不再继续烧算力"的关键</b>：
     * 只置标志而不关流，provider 最多在读完当前缓冲后退出，上游那一侧仍在生成。
     *
     * <p><b>竞态已处理</b>：取消可能发生在 provider 把上游资源交给本对象<b>之前</b>
     * （比如连接刚建立就被取消）。两侧各自检查，故两种先后顺序都能关到：
     * <ul>
     *     <li>先 {@link #cancel()} 后 {@link #bind(Closeable)} → bind 发现已取消，当场关掉；</li>
     *     <li>先 {@link #bind(Closeable)} 后 {@link #cancel()} → cancel 从引用里取出来关掉。</li>
     * </ul>
     * {@code closeUpstream()} 用 {@code getAndSet(null)} 保证只关一次、且重复调用安全。
     *
     * <p>线程安全：{@code cancel()} 由工作线程（写帧失败时）调用，{@code isCancelled()}
     * 由同一工作线程在读取循环里轮询 —— 同线程，但两侧仍用原子类型，
     * 因为将来的自动路由/多线程消费不能假设这一点。
     *
     * @author nexus
     */
    final class CancelToken {

        private static final Logger log = LoggerFactory.getLogger(CancelToken.class);

        /** 是否已取消。 */
        private final AtomicBoolean cancelled = new AtomicBoolean(false);

        /** 上游资源（通常是响应流）；未 bind 或已关闭时为 {@code null}。 */
        private final AtomicReference<Closeable> upstream = new AtomicReference<>();

        /**
         * 登记上游资源，取消时由本对象负责关闭。
         *
         * <p>每条流一个 token，只 bind 一次；重复 bind 会覆盖旧引用（旧资源不再受本对象管理），
         * 故调用方不要这么用。
         *
         * @param resource 可关闭的上游资源（如 {@code InputStream}）；为 {@code null} 时忽略
         */
        public void bind(Closeable resource) {
            if (resource == null) {
                return;
            }
            upstream.set(resource);
            if (cancelled.get()) {
                // 取消发生在 bind 之前：cancel() 那一刻引用还是 null，它关不到，这里补一次
                closeUpstream();
            }
        }

        /**
         * 是否已被取消 —— provider 的读取循环应每轮检查一次。
         *
         * @return {@code true} 表示应当停止读取上游并尽快返回
         */
        public boolean isCancelled() {
            return cancelled.get();
        }

        /**
         * 取消本次调用：置位标志并关闭已登记的上游资源。
         *
         * <p>幂等，可在异常路径上放心重复调用。
         */
        public void cancel() {
            cancelled.set(true);
            closeUpstream();
        }

        private void closeUpstream() {
            Closeable resource = upstream.getAndSet(null);
            if (resource == null) {
                return;
            }
            try {
                resource.close();
            } catch (IOException ex) {
                // 关流失败无需上报：连接本来就要废弃，且此处位于异常路径上，
                // 再抛一个异常只会覆盖掉"客户端已断开"这个真正的原因
                log.debug("关闭上游资源失败（可忽略）：{}", ex.getMessage());
            }
        }
    }
}
