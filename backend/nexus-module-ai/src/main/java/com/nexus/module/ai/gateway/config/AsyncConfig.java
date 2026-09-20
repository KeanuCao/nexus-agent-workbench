package com.nexus.module.ai.gateway.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * 流式对话的异步载体（决策 D8）：<b>专用有界线程池</b> + <b>单线程软上限定时器</b>。
 *
 * <p>两者的分工是"谁干活"与"谁收尾"：工作线程池跑上游调用（每个流占一个线程直到结束，
 * 池大小即并发流上限），定时器只管在硬超时之前把超长的流主动收尾（发 {@code done(timeout)}）。
 * 分开还有一个现实理由：定时任务若跑在业务池里，池满时"该收尾的流"连排队的机会都没有。
 *
 * <p>为什么不用 WebFlux：本项目是 Spring MVC + MyBatis-Plus 的<b>阻塞式栈</b>，
 * 为一条接口引入 WebFlux 会把两套编程模型放进同一个应用；而 Java 17 没有虚拟线程。
 * 代价是每个进行中的流占一个本池线程直到结束 —— 池大小即并发流上限。
 *
 * <p><b>拒绝策略是本设计的承重件</b>：队列容量取 0 + {@link ThreadPoolExecutor.AbortPolicy}，
 * 池满时立刻拒绝而不是排队（无界队列 + 长连接 = 内存慢性泄漏）。
 * {@code ThreadPoolTaskExecutor} 会把 {@code RejectedExecutionException}
 * 包装成 {@code TaskRejectedException} 抛出 —— 调用方（{@code ChatService}）捕获它并转成
 * <b>HTTP 503 + 20100</b>（开流前，状态码还改得动，见设计 §3.3）。这条链路上任何一环改成
 * "排队等待"，表现都会变成"页面一直转圈而没有报错"。
 *
 * <p><b>为什么返回 {@code ThreadPoolTaskExecutor} 而不是 {@code Executor}</b>：
 * {@code submit()} / {@code execute()} 需要在自己的类型上调用，且调用方要按
 * {@code TaskRejectedException} 分类处置，签名明确比"注入一个 Executor 再强转"干净。
 *
 * <p>⚠️ <b>一个已知的连带影响</b>：Boot 的 {@code TaskExecutionAutoConfiguration} 用的是
 * {@code @ConditionalOnMissingBean(Executor.class)} —— 本 Bean 一旦存在，Boot 就不再创建
 * {@code applicationTaskExecutor}。本项目没有 {@code @Async} 方法，也没有返回
 * {@code Callable} 的接口（{@code SseEmitter} 路径不消费 MVC 的异步任务执行器），
 * 故不构成问题；但**将来若新增 {@code @Async}，要显式指定使用哪个执行器**，
 * 否则会落到默认的单线程/SimpleAsync 实现上。
 *
 * @author nexus
 */
@Configuration
public class AsyncConfig {

    /** Bean 名：注入处用 {@code @Qualifier} 或按名取，避免与将来其他执行器混淆。 */
    public static final String CHAT_EXECUTOR_BEAN_NAME = "chatStreamExecutor";

    /**
     * Bean 名：服务端软上限（{@code nexus.ai.soft-timeout-ms}）的定时器。
     *
     * <p>与上面的工作线程池<b>刻意分开</b>：定时任务若跑在业务池里，池满时会连"该收尾的流"
     * 都排不进去，表现为"答到一半既没有 done 也没有 error"。
     */
    public static final String CHAT_TIMEOUT_SCHEDULER_BEAN_NAME = "chatTimeoutScheduler";

    /**
     * 对话流式调用线程池。
     *
     * <p>刻意<b>不</b>设置 {@code waitForTasksToCompleteOnShutdown}（保持默认的
     * {@code shutdownNow()}）：容器停止时客户端的连接本来就要断，让 worker 立刻收工
     * 比"等它跑完"更干净，也不会因为一个长回答把 JVM 退出拖住。
     * 更关键的是，被中断的 worker 走的是与"客户端断开"完全相同的那条清理路径
     * （{@code send()} 抛异常 → 置取消标志 → 关上游流），不需要第二条代码路径。
     *
     * <p>本 Bean 的 {@code initialize()} 由 Spring 在 {@code afterPropertiesSet()} 里调用
     * （{@code ThreadPoolTaskExecutor} 实现了 {@code InitializingBean}），此处无需手工调用。
     *
     * @param properties 网关配置（{@code nexus.ai.executor.*}）
     * @return 已按配置装配的线程池
     */
    @Bean(name = CHAT_EXECUTOR_BEAN_NAME)
    public ThreadPoolTaskExecutor chatStreamExecutor(AiProperties properties) {
        AiProperties.Executor config = properties.getExecutor();

        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(config.getCoreSize());
        executor.setMaxPoolSize(config.getMaxSize());
        executor.setQueueCapacity(config.getQueueCapacity());
        // 线程名带前缀：出问题时 jstack / 日志里要能一眼认出"这条线程在跑谁的活"
        executor.setThreadNamePrefix("chat-stream-");
        // 显式声明拒绝策略：虽然它也是 ThreadPoolTaskExecutor 的默认值，
        // 但"池满即拒绝、不排队"是本设计的承重约定，写在代码里才不会被后来者改掉
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        return executor;
    }

    /**
     * 服务端软上限（{@code nexus.ai.soft-timeout-ms}）的定时器。
     *
     * <p>它承担的是"流总时长的第二个机制"（设计 §4.4）：emitter 的硬超时到点会
     * <b>直接掐断连接、一个字节都写不出</b>，所以需要另一条更早的定时任务，
     * 在硬超时之前主动发一帧 {@code done} + {@code finishReason=timeout}，
     * 让前端能正常收尾（否则页面永远停在"生成中"）。
     *
     * <p><b>单线程足够</b>：每个定时任务的执行体只有"在同一把会话锁上写一帧 + complete"，
     * 既不等待上游、也不做 IO 重活；同时到点的流只是排一下队，代价远小于多线程带来的调度不确定性。
     *
     * <p>{@code daemon=true} 且由容器负责销毁（{@link ThreadPoolTaskScheduler} 实现了
     * {@code DisposableBean}，{@code destroy()} 里会 {@code shutdown()}）——
     * 两条都要有：daemon 保证它不会拖住 JVM 退出，容器销毁保证不会在停机后还留着任务。
     *
     * @return 单线程调度器
     */
    @Bean(name = CHAT_TIMEOUT_SCHEDULER_BEAN_NAME)
    public ThreadPoolTaskScheduler chatTimeoutScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        // 线程名带前缀：jstack / 日志里一眼能认出"这条线程在跑流式对话的定时任务"
        scheduler.setThreadNamePrefix("chat-timeout-");
        scheduler.setDaemon(true);
        return scheduler;
    }
}
