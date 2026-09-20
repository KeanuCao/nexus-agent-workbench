package com.nexus.module.ai.gateway.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.common.exception.BusinessException;
import com.nexus.common.result.ResultCode;
import com.nexus.module.ai.gateway.AiModelService;
import com.nexus.module.ai.gateway.ModelDescriptor;
import com.nexus.module.ai.gateway.ModelType;
import com.nexus.module.ai.gateway.config.AiProperties;
import com.nexus.module.ai.gateway.dto.ChatMessage;
import com.nexus.module.ai.gateway.dto.ChatStreamDone;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.function.Consumer;

/**
 * 云端 DeepSeek 的 provider：{@code POST /chat/completions}，把上游的 <b>OpenAI 风格 SSE</b>
 * 翻译成本项目的分片。
 *
 * <h2>上游协议（与 Ollama 的差异全在本类内部消化）</h2>
 * 响应是事件流，每行一个 {@code data:} 负载，最后以 {@code data: [DONE]} 收尾：
 * <pre>
 * data: {"choices":[{"index":0,"delta":{"content":"你"},"finish_reason":null}]}
 *
 * data: {"choices":[{"index":0,"delta":{"content":""},"finish_reason":"stop"}]}
 *
 * data: [DONE]
 * </pre>
 * 与 NDJSON 的三处差别，逐条都在本类里被抹平：① 每行带 {@code data:} 前缀、负载后可能跟一个空格；
 * ② 结束信号有两种（{@code finish_reason} 与 {@code [DONE]}），必须先记住前者、由后者收尾；
 * ③ 事件之间有<b>空行</b>分隔，还可能出现 {@code :} 开头的注释行（保活用）。
 *
 * <p>读取形状、失败约定与 {@link OllamaService} 完全一致（同一个决策 D1 / §4.4），
 * 差异只在行解析 —— 两家的"怎么调"是同构的，"说什么"才不同。
 *
 * <h2>密钥缺失不是启动错误</h2>
 * {@code DEEPSEEK_API_KEY} 来自容器环境变量，缺失时<b>启动照常</b>（缺密钥只让"云端那一半"测不了），
 * 但<b>调用时直接按 20100 失败、不发网络请求</b>：一次注定 401 的往返既慢，又会在日志里留下一条
 * 指向"上游不可用"的误导性记录，而真正的原因是本服务没配密钥。见设计 §6.4。
 *
 * @author nexus
 */
@Component
public class DeepSeekService implements AiModelService {

    private static final Logger log = LoggerFactory.getLogger(DeepSeekService.class);

    /** SSE 字段名前缀（含冒号）。负载在其后，可能还跟一个空格 —— 故取值时先 {@code strip()}。 */
    private static final String DATA_PREFIX = "data:";

    /** OpenAI 风格流的结束标记（不是 JSON，是一个字面量）。 */
    private static final String DONE_MARKER = "[DONE]";

    /** 注释行前缀：SSE 规范里以 {@code :} 开头的行是注释（常用于保活）。 */
    private static final char COMMENT_PREFIX = ':';

    private static final String BEARER_PREFIX = "Bearer ";

    private final RestClient restClient;

    private final ObjectMapper objectMapper;

    /** 对话接口路径（{@code nexus.ai.deepseek.chat-path}）。 */
    private final String chatPath;

    /**
     * 密钥。为空时 {@link #stream} 不发任何网络请求。
     *
     * <p>⚠️ 本字段<b>绝不可</b>整体打进日志（本类的构造期日志只打"有没有配"，不打值）。
     */
    private final String apiKey;

    /** 能力自述（构造期定型一次，理由同 {@link OllamaService}）。 */
    private final ModelDescriptor descriptor;

    public DeepSeekService(AiProperties properties, ObjectMapper objectMapper) {
        AiProperties.DeepSeek deepseek = properties.getDeepseek();
        this.objectMapper = objectMapper;
        this.chatPath = deepseek.getChatPath();
        this.apiKey = deepseek.getApiKey() == null ? "" : deepseek.getApiKey().trim();
        this.descriptor = new ModelDescriptor(ModelType.DEEPSEEK, deepseek.getModel(), true, 65_536,
                ModelDescriptor.CostTier.LOW);

        Duration timeout = Duration.ofMillis(properties.getReadTimeoutMs());
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(timeout);
        requestFactory.setReadTimeout(timeout);

        this.restClient = RestClient.builder()
                .requestFactory(requestFactory)
                .baseUrl(deepseek.getBaseUrl())
                .build();

        if (this.apiKey.isEmpty()) {
            // 启动期就喊一声：等到第一个"选云端模型"的请求进来才报 20100 时，
            // 现场看起来像"上游挂了"，而原因其实在环境变量里（设计 §6.4 已写明这是约定行为）
            log.warn("未配置 DEEPSEEK_API_KEY（nexus.ai.deepseek.api-key 为空）—— "
                    + "云端 DeepSeek 将直接按 20100 失败，本地 Ollama 不受影响");
        }
        log.info("DeepSeek provider 就绪：baseUrl={} chatPath={} model={} readTimeoutMs={} apiKey已配置={}",
                deepseek.getBaseUrl(), chatPath, descriptor.modelName(), properties.getReadTimeoutMs(),
                !this.apiKey.isEmpty());
    }

    @Override
    public ModelDescriptor descriptor() {
        return descriptor;
    }

    @Override
    public void stream(List<ChatMessage> messages, ModelDescriptor descriptor,
                       Consumer<Chunk> onChunk, CancelToken cancelToken) {
        if (apiKey.isEmpty()) {
            log.warn("未配置 DEEPSEEK_API_KEY，本次对话直接按 20100 失败（不发网络请求）");
            throw new BusinessException(ResultCode.CHAT_UPSTREAM_UNAVAILABLE);
        }

        UpstreamChatRequest requestBody = UpstreamChatRequest.of(descriptor, messages);
        try {
            restClient.post()
                    .uri(chatPath)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header(HttpHeaders.AUTHORIZATION, BEARER_PREFIX + apiKey)
                    .body(requestBody)
                    // ★ 读必须在回调内部（决策 D1），理由见 OllamaService 的类注释
                    .exchange((request, response) -> {
                        if (response.getStatusCode().isError()) {
                            // 4xx 里最常见的是 401（密钥无效/欠费）与 400（参数）；状态码是排查起点。
                            // 刻意不回显响应体：它可能带上游返回的敏感信息，而状态码 + 本服务日志已够定位
                            log.warn("DeepSeek 返回错误状态：status={} path={}", response.getStatusCode(), chatPath);
                            throw new BusinessException(ResultCode.CHAT_UPSTREAM_UNAVAILABLE);
                        }
                        InputStream upstreamBody = response.getBody();
                        cancelToken.bind(upstreamBody);
                        parseStream(upstreamBody, onChunk, cancelToken);
                        return null;
                    });
        } catch (ResourceAccessException ex) {
            log.warn("调用 DeepSeek 失败：type={} message={}", ex.getClass().getSimpleName(), ex.getMessage());
            throw new BusinessException(ResultCode.CHAT_UPSTREAM_UNAVAILABLE);
        } catch (RestClientException ex) {
            // 非网络类失败 = 本服务侧缺陷（如请求体序列化），按系统异常处置，不伪装成"上游不可用"
            log.error("调用 DeepSeek 时出现非网络类失败（按系统异常处置）：type={}",
                    ex.getClass().getName(), ex);
            throw ex;
        }
    }

    /**
     * 逐行解析 OpenAI 风格 SSE。
     *
     * <p><b>包内可见是刻意的</b>（理由同 {@link OllamaService#parseStream}）：单测要能喂
     * "被 TCP 切成两半的半个事件"，而不必搭 HTTP 服务。
     *
     * <p>行缓冲同样交给 {@link BufferedReader#readLine()}（见 OllamaService 的同名方法注释）。
     *
     * @param body        上游响应体
     * @param onChunk     分片回调
     * @param cancelToken 取消信号
     * @throws BusinessException 读流过程中上游断开（{@code 20100}）；被取消的情形不会走到这里
     */
    void parseStream(InputStream body, Consumer<Chunk> onChunk, CancelToken cancelToken) {
        // 解析状态按调用新建：provider 是单例、stream(...) 会被并发调用，
        // 把"上一片给了什么 finish_reason"存进字段会让两条并发的流互相串味
        ParseState state = new ParseState();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (cancelToken.isCancelled()) {
                    return;
                }
                handleLine(line, onChunk, state);
            }
            if (!state.terminalEmitted) {
                // 连接正常结束却没有 [DONE]：协议上不该发生（真截断通常以 IOException 告终），
                // 记一条 warn 并按已收到的 finish_reason 收尾 —— 比让这条流"没有终止片"要好：
                // 上游少给一个标记，不该变成前端的"永远生成中"
                log.warn("DeepSeek 的流在没有 [DONE] 标记的情况下结束，按 {} 收尾",
                        state.resolveFinishReason());
                onChunk.accept(Chunk.finished(state.resolveFinishReason()));
                state.terminalEmitted = true;
            }
        } catch (IOException ex) {
            if (cancelToken.isCancelled()) {
                // ★ 取消后读上游抛的 IOException 不算故障（同 OllamaService，详见那里的注释）
                log.debug("上游读取因取消而中断（正常路径）：{}", ex.getMessage());
                return;
            }
            log.warn("读取 DeepSeek 流失败（上游可能在响应中途断开）：{}", ex.getMessage());
            throw new BusinessException(ResultCode.CHAT_UPSTREAM_UNAVAILABLE);
        }
    }

    /**
     * 解析一行 SSE。
     *
     * @param line    一行（可能为空行、注释行、非 data 行）
     * @param onChunk 分片回调
     * @param state   本次调用的解析状态
     * @throws BusinessException 该行是上游的错误报文（{@code 20100}）
     */
    private void handleLine(String line, Consumer<Chunk> onChunk, ParseState state) {
        if (line.isEmpty() || line.charAt(0) == COMMENT_PREFIX) {
            // 空行 = 事件分隔符；':' 开头 = 注释（保活）。两种都不是载荷
            return;
        }
        if (!line.startsWith(DATA_PREFIX)) {
            // 本接口只消费 data 行。SSE 规范里还有 event: / id: / retry:，
            // OpenAI 兼容实现并不使用它们 —— 与其"顺便支持"，不如让它们明确落空
            return;
        }
        // 前缀后可能带一个空格（OpenAI 官方报文就带），故取值先 strip：
        // 不 strip 的话 " [DONE]" 匹配不上结束标记，流会一直等到 EOF 才收尾
        String payload = line.substring(DATA_PREFIX.length()).strip();

        if (DONE_MARKER.equals(payload)) {
            if (!state.terminalEmitted) {
                onChunk.accept(Chunk.finished(state.resolveFinishReason()));
                state.terminalEmitted = true;
            }
            return;
        }

        JsonNode node;
        try {
            node = objectMapper.readTree(payload);
        } catch (JsonProcessingException ex) {
            // 跳过而不是让整条流失败（理由同 OllamaService：一行读不懂不该作废整段回答）；
            // 同样刻意不把负载内容或 Jackson 报文（自带内容片段）打进日志
            log.warn("DeepSeek 返回了无法解析的 data 行，已跳过：length={} type={}",
                    payload.length(), ex.getClass().getSimpleName());
            return;
        }

        if (node.hasNonNull("error")) {
            // OpenAI 风格的错误体是 {"error":{"message":"...","type":"..."}}，也有实现直接给字符串
            JsonNode error = node.path("error");
            String description = error.hasNonNull("message") ? error.path("message").asText() : error.asText();
            log.warn("DeepSeek 返回错误报文：{}", description);
            throw new BusinessException(ResultCode.CHAT_UPSTREAM_UNAVAILABLE);
        }

        JsonNode choices = node.path("choices");
        if (!choices.isArray() || choices.isEmpty()) {
            // 有些实现会先发一帧只带 id / usage 的负载，没有 choices：跳过即可
            return;
        }
        JsonNode choice = choices.get(0);

        String content = choice.path("delta").path("content").asText("");
        if (!content.isEmpty()) {
            onChunk.accept(Chunk.text(content));
        }

        // finish_reason 为 null（流未结束）时 asText(null) 原样返回 null，正好用作"还没结束"的判据
        String finishReason = choice.path("finish_reason").asText(null);
        if (finishReason != null && !finishReason.isBlank() && !state.terminalEmitted) {
            // 结束片在这里就吐：此时语义已经完整（上游明确说了为什么结束），
            // 后面那个 data: [DONE] 只是流终止标记，不再重复产片
            state.finishReason = mapFinishReason(finishReason);
            onChunk.accept(Chunk.finished(state.finishReason));
            state.terminalEmitted = true;
        }
    }

    /**
     * 把上游的 {@code finish_reason} 映射成契约里的结束原因（映射规则同 OllamaService §6.2）。
     *
     * @param upstreamReason 上游的 {@code finish_reason}，如 {@code stop} / {@code length}
     * @return {@code length} 或 {@code stop}
     */
    private static String mapFinishReason(String upstreamReason) {
        return ChatStreamDone.FINISH_LENGTH.equals(upstreamReason)
                ? ChatStreamDone.FINISH_LENGTH
                : ChatStreamDone.FINISH_STOP;
    }

    /**
     * 一次流式调用的解析状态（每次调用新建，见 {@link #parseStream}）。
     *
     * @author nexus
     */
    private static final class ParseState {

        /** 上游给出的结束原因（映射后）；尚未给出时为 {@code null}。 */
        private String finishReason;

        /** 是否已经产出过结束片：保证一条流只产一片。 */
        private boolean terminalEmitted;

        /**
         * 收尾时该用的结束原因：上游没说过就用 {@code stop}。
         *
         * @return {@code stop} / {@code length}
         */
        private String resolveFinishReason() {
            return finishReason == null ? ChatStreamDone.FINISH_STOP : finishReason;
        }
    }
}
