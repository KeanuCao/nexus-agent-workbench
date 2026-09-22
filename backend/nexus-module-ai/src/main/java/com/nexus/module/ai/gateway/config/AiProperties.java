package com.nexus.module.ai.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 统一 AI 网关配置：绑定 {@code nexus.ai.*}（见 nexus-start 的 {@code application.yml}）。
 *
 * <p><b>注册方式</b>：{@code @Component + @ConfigurationProperties} 自注册 ——
 * 靠 {@code NexusApplication} 的 {@code scanBasePackages = "com.nexus"} 扫到，
 * 因此<b>不需要改 nexus-start</b>。刻意不照抄同项目 {@code NexusHealthProperties} 的
 * {@code @EnableConfigurationProperties} 写法：那种写法要求在启动类上登记，而启动类在
 * <b>下游</b>模块（module-ai 看不到它），本模块的配置若依赖下游登记，就成了一条反向依赖。
 *
 * <p>字段都给了与 {@code application.yml} 一致的 Java 默认值：yml 片段缺失时行为不退化
 * （例如单测里 {@code new AiProperties()} 就能得到可用的配置）。
 *
 * <p>⚠️ <b>不要把本对象整体打进日志</b>：{@link DeepSeek#getApiKey()} 是密钥。要打就逐字段打。
 *
 * @author nexus
 */
@Component
@ConfigurationProperties(prefix = "nexus.ai")
public class AiProperties {

    /**
     * {@code modelType} 缺省（{@code null}）时用哪个 provider（决策 D6）。
     *
     * <p>取值即 {@code ModelType} 枚举名；非法取值会让 {@code UserSelectedModelRouter}
     * 在<b>启动期</b>失败（有意的 fail-fast：配置写错就该现在响，而不是等第一个"没选模型"的请求进来
     * 才报成"不支持的模型类型"）。
     */
    private String defaultModel = "OLLAMA";

    /**
     * emitter 硬超时（毫秒）：到点连接直接结束，<b>发不出任何帧</b>。
     *
     * <p>与 {@code spring.mvc.async.request-timeout} 同源：{@code ChatService} 构造
     * {@code SseEmitter} 时显式传本值，不依赖那个全局默认值兜底 —— 两处同源，
     * 避免"配了个值却不生效"。
     */
    private long streamTimeoutMs = 300_000L;

    /**
     * 服务端软上限（毫秒）：网关自己的定时任务，在硬超时<b>之前</b>触发，
     * 发 {@code event: done} 且 {@code finishReason=timeout}。
     *
     * <p>必须明显小于 {@link #streamTimeoutMs}（当前 290s / 300s），
     * 否则软上限永远来不及发 —— 硬超时一到，连接是直接断的，一个字节都写不出去。
     */
    private long softTimeoutMs = 290_000L;

    /**
     * 上游"两次读之间"的空闲超时（毫秒），<b>不是</b>流的总时长。
     *
     * <p>刻意不沿用 {@code nexus.health.ollama.probe-timeout-ms}（2000）—— 那是探活口径，
     * 用在生成式调用上会让长回答被误杀。
     */
    private long readTimeoutMs = 60_000L;

    /** Ollama（本地）。 */
    private Ollama ollama = new Ollama();

    /** DeepSeek（云端）。 */
    private DeepSeek deepseek = new DeepSeek();

    /** 流式调用的专用线程池（决策 D8）。 */
    private Executor executor = new Executor();

    /**
     * Ollama 配置。
     *
     * @author nexus
     */
    public static class Ollama {

        /** 服务基址：容器内是服务名 {@code http://ollama:11434}，Windows 本地开发用环境变量覆盖。 */
        private String baseUrl = "http://ollama:11434";

        /** 对话接口路径（Ollama 的 NDJSON 流式接口）。 */
        private String chatPath = "/api/chat";

        /**
         * 向量化接口路径（阶段3 新增，RAG 的 {@code OllamaEmbeddingService} 用它）。
         *
         * <p>它与 {@link #chatPath} 同类：都是 Ollama 的上游路径。写在 yml 的
         * {@code nexus.ai.ollama} 下而不是 {@code rag} 下，是为了让<b>两条上游配置同处一地</b>
         * —— base-url 是两者共用的，把同一个上游拆到两个配置块里，将来换 Ollama 版本时
         * 就得记住改两个地方。
         *
         * <p>单独做成配置项（而不是写死在实现里）的理由：换 Ollama 版本时若端点改了名字，
         * 只改这一行；实现内部那条"退化为旧的 {@code /api/embeddings} 逐条调用"的退路也由它兜底。
         * <p>✅ 2026-09-22 探针 A/B 实测：该端点存在、响应形状 {@code {"embeddings":[[...]]}}、支持批量。
         */
        private String embedPath = "/api/embed";

        /** 模型名（会原样出现在 {@code meta.model} 里，也是验收 2.2-1 的判据）。 */
        private String model = "qwen2.5:7b";

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getChatPath() {
            return chatPath;
        }

        public void setChatPath(String chatPath) {
            this.chatPath = chatPath;
        }

        public String getEmbedPath() {
            return embedPath;
        }

        public void setEmbedPath(String embedPath) {
            this.embedPath = embedPath;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }
    }

    /**
     * DeepSeek 配置。
     *
     * @author nexus
     */
    public static class DeepSeek {

        /** 服务基址（OpenAI 兼容接口）。 */
        private String baseUrl = "https://api.deepseek.com";

        /** 对话接口路径（OpenAI 风格的 SSE）。 */
        private String chatPath = "/chat/completions";

        /** 模型名。 */
        private String model = "deepseek-chat";

        /**
         * 密钥。<b>只能来自环境变量</b>（compose 的 {@code env_file} 注入，见
         * {@code docker-compose/.env.local}）—— 绝不写进仓库。
         *
         * <p>为空不是启动错误：容器缺密钥也要能照常起来（缺密钥只让"云端那半"测不了），
         * provider 在启动时打一条 warn、调用时直接按 {@code 20100} 失败，不发无谓的网络请求。
         */
        private String apiKey = "";

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getChatPath() {
            return chatPath;
        }

        public void setChatPath(String chatPath) {
            this.chatPath = chatPath;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }
    }

    /**
     * 流式调用的专用线程池配置（决策 D8）。
     *
     * <p>池大小即<b>并发流上限</b>：每个进行中的流占一个线程直到结束
     * （被占用的是本池线程，不是 Tomcat 请求线程 —— 后者在 controller 返回 emitter 时即释放）。
     *
     * @author nexus
     */
    public static class Executor {

        /** 常驻线程数。 */
        private int coreSize = 4;

        /** 最大线程数 = 并发流上限；超出即拒绝（不是排队）。 */
        private int maxSize = 16;

        /**
         * 队列容量。<b>取 0 是有意的</b>：无界队列 + 长连接 = 内存慢性泄漏
         * （请求永远不被拒绝，只是越堆越多）；取 0 时线程池直接扩到 maxSize，
         * 再超就抛拒绝异常 → 由上层转成 HTTP 503 + 20100。
         */
        private int queueCapacity = 0;

        public int getCoreSize() {
            return coreSize;
        }

        public void setCoreSize(int coreSize) {
            this.coreSize = coreSize;
        }

        public int getMaxSize() {
            return maxSize;
        }

        public void setMaxSize(int maxSize) {
            this.maxSize = maxSize;
        }

        public int getQueueCapacity() {
            return queueCapacity;
        }

        public void setQueueCapacity(int queueCapacity) {
            this.queueCapacity = queueCapacity;
        }
    }

    public String getDefaultModel() {
        return defaultModel;
    }

    public void setDefaultModel(String defaultModel) {
        this.defaultModel = defaultModel;
    }

    public long getStreamTimeoutMs() {
        return streamTimeoutMs;
    }

    public void setStreamTimeoutMs(long streamTimeoutMs) {
        this.streamTimeoutMs = streamTimeoutMs;
    }

    public long getSoftTimeoutMs() {
        return softTimeoutMs;
    }

    public void setSoftTimeoutMs(long softTimeoutMs) {
        this.softTimeoutMs = softTimeoutMs;
    }

    public long getReadTimeoutMs() {
        return readTimeoutMs;
    }

    public void setReadTimeoutMs(long readTimeoutMs) {
        this.readTimeoutMs = readTimeoutMs;
    }

    public Ollama getOllama() {
        return ollama;
    }

    public void setOllama(Ollama ollama) {
        this.ollama = ollama;
    }

    /**
     * DeepSeek 配置。
     *
     * <p>⚠️ getter / setter 刻意命名为 {@code getDeepseek} / {@code setDeepseek}（小写 s），
     * <b>不要</b>改成 {@code getDeepSeek}：Spring Boot 把 JavaBean 属性名转成"中划线形式"时
     * 会在大写字母前插连字符 —— 属性名 {@code deepSeek} 会变成 {@code deep-seek}，
     * 而 yml 里的键是 {@code nexus.ai.deepseek.*}（设计 §6.2 已定），
     * 两者对不上时<b>不会报错，只会静默不绑定</b>，结果就是"配置明明写了却没生效"。
     */
    public DeepSeek getDeepseek() {
        return deepseek;
    }

    public void setDeepseek(DeepSeek deepseek) {
        this.deepseek = deepseek;
    }

    public Executor getExecutor() {
        return executor;
    }

    public void setExecutor(Executor executor) {
        this.executor = executor;
    }
}
