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
 * 本地 Ollama 的 provider：{@code POST /api/chat}，把上游的 <b>NDJSON</b> 流翻译成本项目的分片。
 *
 * <h2>上游协议（这是本类存在的全部理由）</h2>
 * 请求 {@code {"model":"qwen2.5:7b","messages":[...],"stream":true}}，响应是<b>每行一个 JSON 对象</b>：
 * <pre>
 * {"model":"qwen2.5:7b","message":{"role":"assistant","content":"你"},"done":false}
 * {"model":"qwen2.5:7b","message":{"role":"assistant","content":"好"},"done":false}
 * {"model":"qwen2.5:7b","message":{"role":"assistant","content":""},"done":true,"done_reason":"stop"}
 * </pre>
 * 这类差异（NDJSON vs 另一家的 SSE、{@code done:true} vs {@code [DONE]}）<b>全部消化在本类内部</b>，
 * 出口统一成 {@link Chunk}（设计 §4.3）—— 这正是"统一网关"相对"nginx 直接转发"的价值所在。
 *
 * <h2>读取形状：必须在 extractor 回调内部读完（决策 D1）</h2>
 * 用 {@code RestClient} 的 {@code exchange(...)}，并且在<b>回调内部</b>把流读完：
 * {@code DefaultRestClient.exchangeInternal} 在回调返回后的 {@code finally} 里才
 * {@code clientResponse.close()}，出了回调拿到的就是一条已关闭的流 —— 问题不是"增量与否"，
 * 而是"流还在不在"。回调内读则无论框架何时关都不受影响。
 *
 * <p>⚠️ 与设计 D1 的一处偏差（已在交付说明中上报）：D1 写的是
 * {@code execute(requestCallback, responseExtractor)}，而 Spring Framework 6.1 的
 * {@code RestClient} <b>没有这个方法</b>（它只在 {@code RestTemplate} 上：
 * 已核 6.1.21 源码），{@code RestClient} 提供原始响应访问的唯一入口就是
 * {@code exchange(ExchangeFunction, boolean close)}。这里取的是安全性质完全相同的那条路：
 * <b>读发生在回调内部、响应关闭发生在回调返回之后</b>。
 *
 * <h2>失败约定</h2>
 * 上游不可达 / 超时 / 报错一律转成 {@link BusinessException}（{@code 20100}）；
 * 而<b>取消</b>（客户端断开后取消方关掉了这条流）引发的 {@code IOException} 静默吞掉 —— 见
 * {@link #parseStream(InputStream, Consumer, CancelToken)} 的注释。
 *
 * @author nexus
 */
@Component
public class OllamaService implements AiModelService {

    private static final Logger log = LoggerFactory.getLogger(OllamaService.class);

    private final RestClient restClient;

    private final ObjectMapper objectMapper;

    /** 对话接口路径（{@code nexus.ai.ollama.chat-path}）。 */
    private final String chatPath;

    /**
     * 能力自述。<b>构造期定型一次</b>：注册表的键、{@code meta} 帧的 {@code model}、
     * 日志里的 {@code model=} 都取自它，三处必须是同一份数据。
     */
    private final ModelDescriptor descriptor;

    /**
     * 构造 provider（单例；{@code stream(...)} 会被并发调用，故本类<b>不持有任何按流变化的状态</b>）。
     *
     * @param properties   网关配置（{@code nexus.ai.*}）
     * @param objectMapper 容器里的 Jackson 实例；本类只用它读树（{@code readTree}），
     *                     不映射成 POJO —— 上游的行会随版本增减字段，
     *                     手写 POJO 就得给每个字段配 {@code @JsonIgnoreProperties}，
     *                     而"只取我要的两个字段"在树上天然成立
     */
    public OllamaService(AiProperties properties, ObjectMapper objectMapper) {
        AiProperties.Ollama ollama = properties.getOllama();
        this.objectMapper = objectMapper;
        this.chatPath = ollama.getChatPath();
        this.descriptor = new ModelDescriptor(ModelType.OLLAMA, ollama.getModel(), true, 32_768,
                ModelDescriptor.CostTier.FREE);

        // 连接与读取超时都必须显式设置：RestClient 默认无超时，Ollama 挂起会把工作线程永久占住。
        // 两个超时取同一个来源（read-timeout-ms）：它是"两次读之间"的空闲超时（设计 §4.4），
        // 而"连不上"与"连上后不吐字"对使用者的后果是一样的，不值得为前者再开一个配置键。
        Duration timeout = Duration.ofMillis(properties.getReadTimeoutMs());
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(timeout);
        requestFactory.setReadTimeout(timeout);

        this.restClient = RestClient.builder()
                .requestFactory(requestFactory)
                .baseUrl(ollama.getBaseUrl())
                .build();

        log.info("Ollama provider 就绪：baseUrl={} chatPath={} model={} readTimeoutMs={}",
                ollama.getBaseUrl(), chatPath, descriptor.modelName(), properties.getReadTimeoutMs());
    }

    @Override
    public ModelDescriptor descriptor() {
        return descriptor;
    }

    @Override
    public void stream(List<ChatMessage> messages, ModelDescriptor descriptor,
                       Consumer<Chunk> onChunk, CancelToken cancelToken) {
        UpstreamChatRequest requestBody = UpstreamChatRequest.of(descriptor, messages);
        try {
            restClient.post()
                    .uri(chatPath)
                    // 只声明 Content-Type，刻意不发 Accept：两个上游都不据此协商媒体类型
                    //（NDJSON / SSE 都不在标准 Accept 协商范围内），发一个没人遵守的头
                    // 只会让后来者以为它是契约的一部分
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(requestBody)
                    // ★ 读必须在回调内部（决策 D1）：回调返回后框架立刻 close 掉响应
                    .exchange((request, response) -> {
                        if (response.getStatusCode().isError()) {
                            // 能走到这里说明上游连"流"都没给（模型没拉取、参数不合法等）：
                            // 此时 HTTP 状态码就是判据，顺手把状态码记进日志 —— 它是排查时第一眼要看的东西
                            log.warn("Ollama 返回错误状态：status={} path={}", response.getStatusCode(), chatPath);
                            throw new BusinessException(ResultCode.CHAT_UPSTREAM_UNAVAILABLE);
                        }
                        InputStream upstreamBody = response.getBody();
                        // 交给取消信号保管：客户端断开时由它 close（关流即断上游，而非"我们不再读"）
                        cancelToken.bind(upstreamBody);
                        parseStream(upstreamBody, onChunk, cancelToken);
                        return null;
                    });
        } catch (ResourceAccessException ex) {
            // 连接被拒 / 读超时 / 上游在响应中途断开：RestClient 把 IO 类失败统一包成
            // ResourceAccessException（IOException 的包装）。取消引发的读失败已经在
            // parseStream 内部被识别并静默消化掉了，所以能到这里的都是真故障。
            log.warn("调用 Ollama 失败：type={} message={}", ex.getClass().getSimpleName(), ex.getMessage());
            throw new BusinessException(ResultCode.CHAT_UPSTREAM_UNAVAILABLE);
        } catch (RestClientException ex) {
            // 其余 RestClientException（典型是请求体序列化失败）属**本服务侧缺陷**，不是上游不可用。
            // 原样抛出，由 ChatService 按 50000 处置 —— 见 AiModelService 类注释：
            // "上游真的挂了"与"我们写挂了"必须在日志里分得开。
            log.error("调用 Ollama 时出现非网络类失败（按系统异常处置）：type={}",
                    ex.getClass().getName(), ex);
            throw ex;
        }
    }

    /**
     * 逐行解析 NDJSON，把每一行翻译成 0~2 个分片交给回调。
     *
     * <p><b>包内可见是刻意的</b>：这样单测可以直接喂"被 TCP 切碎的片段"（见
     * {@code FragmentInputStream}），而不必先搭一个 HTTP 服务 —— 行协议的跨分片边界
     * 是本层最容易出错、也最值得钉住的地方（设计 §9 风险 4）。
     *
     * <p><b>行缓冲交给 {@link BufferedReader#readLine()}</b>：它本身就实现了"累积到行尾才算一行"，
     * 且同时认 {@code \n} / {@code \r} / {@code \r\n} 三种行终止符（SSE 规范允许前两种混用）。
     * 自己按块拼字符串是同一个算法的手写版，多出来的只有 bug。
     *
     * @param body        上游响应体（调用方保证在响应关闭前读完）
     * @param onChunk     分片回调
     * @param cancelToken 取消信号
     * @throws BusinessException 读流过程中上游断开（{@code 20100}）；被取消的情形不会走到这里
     */
    void parseStream(InputStream body, Consumer<Chunk> onChunk, CancelToken cancelToken) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (cancelToken.isCancelled()) {
                    // 每轮检查一次：取消之后上游可能还留在缓冲区里的分片，一律不再往下传
                    return;
                }
                handleLine(line, onChunk);
            }
        } catch (IOException ex) {
            if (cancelToken.isCancelled()) {
                // ★ 取消后读上游抛的 IOException **不算故障**。取消方刚刚关掉了这条流，
                //   读循环必然以一个 IOException 告终 —— 这是取消的正常表现，不是上游挂了。
                //   不静默的话，客户端每断开一次日志里就多一条"上游不可用"的假告警，
                //   而那正是排查真实故障时最容易带偏方向的噪音（AiModelService 类注释专门警告过）。
                log.debug("上游读取因取消而中断（正常路径）：{}", ex.getMessage());
                return;
            }
            log.warn("读取 Ollama 流失败（上游可能在响应中途断开）：{}", ex.getMessage());
            throw new BusinessException(ResultCode.CHAT_UPSTREAM_UNAVAILABLE);
        }
    }

    /**
     * 解析一行 NDJSON。
     *
     * <p>一行最多产出两个分片：先文本、后结束（上游的最后一帧正是这个形状：
     * {@code message.content} 为空、{@code done:true}）。
     *
     * @param line    一行（可能为空行）
     * @param onChunk 分片回调
     * @throws BusinessException 该行是上游的错误报文（{@code 20100}）
     */
    private void handleLine(String line, Consumer<Chunk> onChunk) {
        if (line.isBlank()) {
            // 空行不是内容：NDJSON 允许用它保活/分隔，直接跳过
            return;
        }
        JsonNode node;
        try {
            node = objectMapper.readTree(line);
        } catch (JsonProcessingException ex) {
            // 跳过而不是让整条流失败："一行读不懂"不该把已经吐了一半的回答作废。
            // ⚠️ 刻意不把这一行（或 Jackson 的报文 —— 它自带内容片段）打进日志：
            //    对话正文不进日志（设计 §4.5），长度与异常类型足够定位问题。
            log.warn("Ollama 返回了无法解析的行，已跳过：length={} type={}",
                    line.length(), ex.getClass().getSimpleName());
            return;
        }

        if (node.hasNonNull("error")) {
            // Ollama 的失败可以是 HTTP 200 + {"error":"..."}（典型：模型没拉取）。
            // 不认这一条的话，前端只会看到一个"凭空结束、一个字都没有"的回答。
            // 错误文案是上游的诊断信息（如 model 'xxx' not found），不是用户正文，可以进日志。
            log.warn("Ollama 返回错误报文：{}", node.path("error").asText());
            throw new BusinessException(ResultCode.CHAT_UPSTREAM_UNAVAILABLE);
        }

        String content = node.path("message").path("content").asText("");
        if (!content.isEmpty()) {
            onChunk.accept(Chunk.text(content));
        }

        if (node.path("done").asBoolean(false)) {
            onChunk.accept(Chunk.finished(mapFinishReason(node.path("done_reason").asText(null))));
        }
    }

    /**
     * 把上游的 {@code done_reason} 映射成契约里的结束原因。
     *
     * <p>契约只定义了两种"由上游给出"的结束原因（{@code stop} / {@code length}，
     * 见 {@code docs/api/README.md} §6.2）—— {@code timeout} 是服务端软上限，不由 provider 给。
     * Ollama 还可能给出别的取值（如 {@code load}）：一律归 {@code stop}，
     * 既不发明契约之外的线上取值，也不把"模型答完了"错报成"被截断了"。
     *
     * @param upstreamReason 上游的 {@code done_reason}，可为 {@code null}
     * @return {@code length} 或 {@code stop}
     */
    private static String mapFinishReason(String upstreamReason) {
        return ChatStreamDone.FINISH_LENGTH.equals(upstreamReason)
                ? ChatStreamDone.FINISH_LENGTH
                : ChatStreamDone.FINISH_STOP;
    }
}
