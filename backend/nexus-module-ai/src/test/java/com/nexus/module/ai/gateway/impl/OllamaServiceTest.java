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
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link OllamaService} 单元测试 —— <b>喂固定分片文本、断言解析出的片段序列</b>。
 *
 * <p>为什么这一层必须有单测：NDJSON 的行协议是"上游给什么就得认什么"，它既不归编译器管，
 * 也不归类型系统管，而它错了的表现是<b>丢字或整段回答作废</b>（设计 §9 风险 4）。
 * 真实连通性交给 TC-02 的实机用例，这里只钉两件事：<b>正常报文解析成什么序列</b>、
 * <b>报文被切碎时还对不对</b>。
 *
 * <p>关键样本是"跨分片边界"：真实网络里一行动辄被 TCP 切成几段（切在 JSON 中间、
 * 甚至切在一个汉字的三个字节中间）—— 实现靠 {@code BufferedReader.readLine()} 自己拼回来，
 * 这两个用例就是防止有人把它换成"每次读一段就地解析"。
 *
 * @author nexus
 */
class OllamaServiceTest {

    /** 正常的三行报文：两片正文 + 一个空正文的结束行（真实 Ollama 的最后一帧就是这个形状）。 */
    private static final String NDJSON_TWO_CHUNKS = """
            {"model":"qwen2.5:7b","created_at":"2026-09-20T10:00:00Z","message":{"role":"assistant","content":"你"},"done":false}
            {"model":"qwen2.5:7b","created_at":"2026-09-20T10:00:00Z","message":{"role":"assistant","content":"好"},"done":false}
            {"model":"qwen2.5:7b","created_at":"2026-09-20T10:00:00Z","message":{"role":"assistant","content":""},"done":true,"done_reason":"stop"}
            """;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private OllamaService service;

    @BeforeEach
    void setUp() {
        // 单测自己造一个 ObjectMapper：本类只 readTree（读树，不映射成 POJO），
        // 因此不受容器里那份 FAIL_ON_UNKNOWN_PROPERTIES 配置的影响
        service = new OllamaService(new AiProperties(), objectMapper);
    }

    @Test
    @DisplayName("NDJSON：逐行解析出 delta 序列，末行 done=true 收尾为 stop")
    void shouldParseNdjsonIntoDeltasThenDone() {
        List<Chunk> chunks = parse(new FragmentInputStream(NDJSON_TWO_CHUNKS));

        assertEquals(List.of("你", "好"), contents(chunks));
        assertEquals(List.of(ChatStreamDone.FINISH_STOP), finishReasons(chunks));
    }

    @Test
    @DisplayName("★ 跨分片边界（半行）：一行被切成三段，仍解析出完整序列")
    void shouldParseLineSplitAcrossFragments() {
        List<Chunk> chunks = parse(new FragmentInputStream(
                "{\"model\":\"qwen2.5:7b\",\"mess",
                "age\":{\"role\":\"assistant\",\"content\":\"你\"},\"done\":false}\n"
                        + "{\"model\":\"qwen2.5:7b\",\"message\":{\"content\":\"好\"},",
                "\"done\":true,\"done_reason\":\"stop\"}\n"));

        assertEquals(List.of("你", "好"), contents(chunks));
        assertEquals(List.of(ChatStreamDone.FINISH_STOP), finishReasons(chunks));
    }

    @Test
    @DisplayName("★ 更狠的边界：一个汉字的 3 个字节被拆到两次读取里，解码必须自己拼回来")
    void shouldParseLineSplitInsideMultiByteCharacter() {
        String json = NDJSON_TWO_CHUNKS;
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        // 切口落在「你」（UTF-8 三字节）的第一个字节之后 —— 任何"读一段就 new String 一次"
        // 的实现都会在这里解出 U+FFFD，且**不抛异常**（正是最隐蔽的那种坏法）
        int splitAt = "{\"model\":\"qwen2.5:7b\",\"created_at\":\"2026-09-20T10:00:00Z\",\"message\":{\"role\":\"assistant\",\"content\":\""
                .getBytes(StandardCharsets.UTF_8).length + 1;

        List<Chunk> chunks = parse(new FragmentInputStream(
                Arrays.copyOfRange(bytes, 0, splitAt),
                Arrays.copyOfRange(bytes, splitAt, bytes.length)));

        assertEquals(List.of("你", "好"), contents(chunks));
    }

    @Test
    @DisplayName("done_reason=length → 结束原因是 length（触达 token 上限，不是 stop）")
    void shouldMapLengthFinishReason() {
        List<Chunk> chunks = parse(new FragmentInputStream(
                "{\"message\":{\"content\":\"被截断的回答\"},\"done\":false}\n"
                        + "{\"message\":{\"content\":\"\"},\"done\":true,\"done_reason\":\"length\"}\n"));

        assertEquals(List.of(ChatStreamDone.FINISH_LENGTH), finishReasons(chunks));
    }

    @Test
    @DisplayName("空行与空内容分片被跳过：不会产出空文本的 delta")
    void shouldSkipBlankLinesAndEmptyContent() {
        List<Chunk> chunks = parse(new FragmentInputStream(
                "\n{\"message\":{\"content\":\"\"},\"done\":false}\n{\"message\":{\"content\":\"你\"},\"done\":false}\n"));

        assertEquals(List.of("你"), contents(chunks));
        assertTrue(finishReasons(chunks).isEmpty(), "没有 done 行就不该产出结束片");
    }

    @Test
    @DisplayName("上游用一行 error 报文报错（HTTP 200）→ 20100，而不是「答完了但没内容」")
    void shouldFailWith20100WhenUpstreamReportsError() {
        List<Chunk> chunks = new ArrayList<>();

        BusinessException ex = assertThrows(BusinessException.class, () -> service.parseStream(
                new FragmentInputStream("{\"error\":\"model 'qwen2.5:7b' not found, try pulling it first\"}\n"),
                chunks::add, new CancelToken()));

        assertEquals(ResultCode.CHAT_UPSTREAM_UNAVAILABLE.getCode(), ex.getCode());
        assertTrue(chunks.isEmpty(), "报错的那一行不该产出任何分片");
    }

    @Test
    @DisplayName("取消后读上游抛的 IOException 必须静默返回（不算 20100 故障）")
    void shouldReturnSilentlyWhenReadFailsAfterCancel() {
        CancelToken cancelToken = new CancelToken();
        cancelToken.cancel();

        assertDoesNotThrow(() -> service.parseStream(failingStream(), chunk -> { }, cancelToken));
    }

    @Test
    @DisplayName("未被取消时读流失败 → BusinessException(20100)")
    void shouldFailWith20100WhenStreamBreaksWithoutCancel() {
        CancelToken cancelToken = new CancelToken();

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.parseStream(failingStream(), chunk -> { }, cancelToken));

        assertEquals(ResultCode.CHAT_UPSTREAM_UNAVAILABLE.getCode(), ex.getCode());
    }

    @Test
    @DisplayName("发往上游的请求体形状（与 DeepSeek 共用同一结构，此处断言同时钉住两家）")
    void shouldBuildUpstreamRequestBodyAsJson() throws IOException {
        ChatMessage message = new ChatMessage();
        message.setRole(ChatMessage.ROLE_USER);
        message.setContent("用三句话介绍杭州");

        String json = objectMapper.writeValueAsString(
                UpstreamChatRequest.of(service.descriptor(), List.of(message)));

        // 逐字断言：字段名、顺序、以及**没有**混进入参 DTO 的校验方法（如 contentSizeWithinLimit）
        assertEquals("{\"model\":\"qwen2.5:7b\",\"messages\":[{\"role\":\"user\",\"content\":\"用三句话介绍杭州\"}],"
                + "\"stream\":true}", json);
    }

    /**
     * 解析一段固定的文本片段序列。
     *
     * @param body 输入流（生产路径上是上游响应体，测试里是 {@link FragmentInputStream}）
     * @return 解析出的分片（按顺序）
     */
    private List<Chunk> parse(InputStream body) {
        List<Chunk> chunks = new ArrayList<>();
        service.parseStream(body, chunks::add, new CancelToken());
        return chunks;
    }

    /** 只取文本片的内容（结束片的 content 是空串，不参与比较）。 */
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
