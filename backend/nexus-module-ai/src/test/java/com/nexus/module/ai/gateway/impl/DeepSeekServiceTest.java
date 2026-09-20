package com.nexus.module.ai.gateway.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.common.exception.BusinessException;
import com.nexus.common.result.ResultCode;
import com.nexus.module.ai.gateway.AiModelService.CancelToken;
import com.nexus.module.ai.gateway.AiModelService.Chunk;
import com.nexus.module.ai.gateway.config.AiProperties;
import com.nexus.module.ai.gateway.dto.ChatMessage;
import com.nexus.module.ai.gateway.dto.ChatStreamDone;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link DeepSeekService} 单元测试 —— <b>喂固定分片文本、断言解析出的片段序列</b>。
 *
 * <p>与 {@code OllamaServiceTest} 同源（都是"报文的行协议不归编译器管"），
 * 但上游形态完全不同：{@code data:} 前缀（后面可能跟一个空格）、空行分隔、
 * {@code :} 注释行、以及<b>两种结束信号</b>（{@code finish_reason} 与 {@code [DONE]}）——
 * 后者必须先记住前者、由后者收尾，正是最容易实现错的地方。
 *
 * @author nexus
 */
class DeepSeekServiceTest {

    /** 一段完整的上游报文：带一个注释行（保活）、一个空行分隔，并以 {@code [DONE]} 收尾。 */
    private static final String SSE_TWO_CHUNKS = """
            data: {"id":"chat-1","choices":[{"index":0,"delta":{"role":"assistant","content":"你"},"finish_reason":null}]}

            : keep-alive

            data: {"id":"chat-1","choices":[{"index":0,"delta":{"content":"好"},"finish_reason":null}]}

            data: {"id":"chat-1","choices":[{"index":0,"delta":{"content":""},"finish_reason":"stop"}]}

            data: [DONE]
            """;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private DeepSeekService service;

    @BeforeEach
    void setUp() {
        AiProperties properties = new AiProperties();
        // 给一个假密钥：本类测的是行解析，不该被"没配密钥就直接失败"那条分支挡住
        properties.getDeepseek().setApiKey("sk-test-not-a-real-key");
        service = new DeepSeekService(properties, objectMapper);
    }

    @Test
    @DisplayName("OpenAI 风格 SSE：跳过注释行与空行，解析出 delta 序列，finish_reason 收尾为 stop")
    void shouldParseOpenAiStyleSseIntoDeltasThenDone() {
        List<Chunk> chunks = parse(new FragmentInputStream(SSE_TWO_CHUNKS));

        assertEquals(List.of("你", "好"), contents(chunks));
        // 只应有一个结束片：finish_reason 已经给过，后面的 data: [DONE] 不该再产一片
        assertEquals(List.of(ChatStreamDone.FINISH_STOP), finishReasons(chunks));
    }

    @Test
    @DisplayName("★ 跨分片边界（半个事件）：一个 data 行被切成三段，仍解析出完整序列")
    void shouldParseEventSplitAcrossFragments() {
        List<Chunk> chunks = parse(new FragmentInputStream(
                "data: {\"id\":\"chat-1\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"你\"}",
                ",\"finish_reason\":null}]}\ndata: {\"id\":\"chat-1\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"好\"}",
                ",\"finish_reason\":null}]}\ndata: [DONE]\n"));

        assertEquals(List.of("你", "好"), contents(chunks));
        assertEquals(List.of(ChatStreamDone.FINISH_STOP), finishReasons(chunks));
    }

    @Test
    @DisplayName("只有 [DONE]、没有 finish_reason 时：按 stop 收尾（且只产一片）")
    void shouldFinishWithStopWhenDoneMarkerCarriesNoFinishReason() {
        List<Chunk> chunks = parse(new FragmentInputStream(
                "data: {\"choices\":[{\"delta\":{\"content\":\"你\"}}]}\n\ndata: [DONE]\n"));

        assertEquals(List.of("你"), contents(chunks));
        assertEquals(List.of(ChatStreamDone.FINISH_STOP), finishReasons(chunks));
    }

    @Test
    @DisplayName("流结束了却没有 [DONE]：按 stop 补一个结束片，不让前端永远停在「生成中」")
    void shouldFinishWithStopWhenStreamEndsWithoutDoneMarker() {
        List<Chunk> chunks = parse(new FragmentInputStream("data: {\"choices\":[{\"delta\":{\"content\":\"你\"}}]}\n"));

        assertEquals(List.of("你"), contents(chunks));
        assertEquals(List.of(ChatStreamDone.FINISH_STOP), finishReasons(chunks));
    }

    @Test
    @DisplayName("finish_reason=length → 结束原因是 length")
    void shouldMapLengthFinishReason() {
        List<Chunk> chunks = parse(new FragmentInputStream(
                "data: {\"choices\":[{\"delta\":{\"content\":\"被截断的回答\"},\"finish_reason\":\"length\"}]}\n"
                        + "data: [DONE]\n"));

        assertEquals(List.of(ChatStreamDone.FINISH_LENGTH), finishReasons(chunks));
    }

    @Test
    @DisplayName("上游在流内给出错误体 → 20100（而不是把错误当成一次空回答）")
    void shouldFailWith20100WhenUpstreamReportsErrorPayload() {
        BusinessException ex = assertThrows(BusinessException.class, () -> service.parseStream(
                new FragmentInputStream("data: {\"error\":{\"message\":\"Insufficient Balance\",\"type\":\"unknown_error\"}}\n"),
                chunk -> { }, new CancelToken()));

        assertEquals(ResultCode.CHAT_UPSTREAM_UNAVAILABLE.getCode(), ex.getCode());
    }

    @Test
    @DisplayName("未配置密钥：调用时直接 20100（不发注定 401 的网络请求）")
    void shouldFailWith20100WithoutApiKey() {
        AiProperties keyless = new AiProperties();
        keyless.getDeepseek().setApiKey("   ");
        DeepSeekService keylessService = new DeepSeekService(keyless, objectMapper);

        ChatMessage message = new ChatMessage();
        message.setRole(ChatMessage.ROLE_USER);
        message.setContent("你好");

        BusinessException ex = assertThrows(BusinessException.class, () -> keylessService.stream(
                List.of(message), keylessService.descriptor(), chunk -> { }, new CancelToken()));

        assertEquals(ResultCode.CHAT_UPSTREAM_UNAVAILABLE.getCode(), ex.getCode());
    }

    @Test
    @DisplayName("取消后读上游抛的 IOException 必须静默返回（不算 20100 故障）")
    void shouldReturnSilentlyWhenReadFailsAfterCancel() {
        CancelToken cancelToken = new CancelToken();
        cancelToken.cancel();

        assertDoesNotThrow(() -> service.parseStream(failingStream(), chunk -> { }, cancelToken));
    }

    @Test
    @DisplayName("解析状态按调用新建：同一条服务（单例）连续解析两次，第二次不受第一次影响")
    void shouldNotLeakParseStateBetweenCalls() {
        List<Chunk> first = new ArrayList<>();
        service.parseStream(new FragmentInputStream(SSE_TWO_CHUNKS), first::add, new CancelToken());
        List<Chunk> second = new ArrayList<>();
        service.parseStream(new FragmentInputStream(SSE_TWO_CHUNKS), second::add, new CancelToken());

        assertEquals(contents(first), contents(second));
        assertEquals(List.of(ChatStreamDone.FINISH_STOP), finishReasons(second));
    }

    /**
     * 解析一段固定的文本片段序列。
     *
     * @param body 输入流
     * @return 解析出的分片（按顺序）
     */
    private List<Chunk> parse(InputStream body) {
        List<Chunk> chunks = new ArrayList<>();
        service.parseStream(body, chunks::add, new CancelToken());
        return chunks;
    }

    /** 只取文本片的内容。 */
    private static List<String> contents(List<Chunk> chunks) {
        List<String> contents = new ArrayList<>();
        for (Chunk chunk : chunks) {
            if (chunk.finishReason() == null) {
                contents.add(chunk.content());
            }
        }
        return contents;
    }

    /** 只取结束片的原因。 */
    private static List<String> finishReasons(List<Chunk> chunks) {
        List<String> reasons = new ArrayList<>();
        for (Chunk chunk : chunks) {
            if (chunk.finishReason() != null) {
                reasons.add(chunk.finishReason());
            }
        }
        return reasons;
    }

    /** 一个一读就抛 IOException 的流：模拟"上游在响应中途断开"。 */
    private static InputStream failingStream() {
        return new InputStream() {

            @Override
            public int read() throws IOException {
                throw new IOException("Connection reset（模拟上游中途断开）");
            }
        };
    }
}
