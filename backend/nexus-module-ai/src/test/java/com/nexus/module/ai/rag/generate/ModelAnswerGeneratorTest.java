package com.nexus.module.ai.rag.generate;

import com.nexus.common.exception.BusinessException;
import com.nexus.common.exception.SystemException;
import com.nexus.common.result.ResultCode;
import com.nexus.module.ai.gateway.AiModelFactory;
import com.nexus.module.ai.gateway.AiModelService;
import com.nexus.module.ai.gateway.ModelDescriptor;
import com.nexus.module.ai.gateway.ModelType;
import com.nexus.module.ai.gateway.dto.ChatMessage;
import com.nexus.module.ai.gateway.dto.ChatStreamDone;
import com.nexus.module.ai.rag.config.RagProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ModelAnswerGenerator} 单元测试 —— <b>把阶段2 的流式端口适配成"一次性"的那一层</b>（设计 §7.1）。
 *
 * <p>它防的三件事：
 * <ol>
 *     <li><b>分片没拼起来 / 拼反了</b>：流式端口的语义是"来了就回调"，累积顺序错了的表现是答案
 *         语句颠倒或丢字 —— 而 HTTP 仍是 200、日志里只有字符数，肉眼看不出来；</li>
 *     <li><b>把失败吞掉</b>：上游不可用必须<b>原样上抛</b> {@code BusinessException(20100)}（出口是
 *         HTTP 503），否则会造出一个"看着成功其实没答"的响应（设计 §3.7 点名的形态）；</li>
 *     <li><b>配置写错时悄悄兜个默认模型</b>：{@code answer-model-type} 为空就是配置缺陷，
 *         兜默认会让"配置写错了"以"答案来自另一个模型"的形态出现，极难倒查（见 {@code resolveModelType}）。</li>
 * </ol>
 *
 * <p>provider 用 Mockito 假实现（项目既有单测的同一手法，见 {@code UserSelectedModelRouterTest}）：
 * 本类与上游之间只隔着 {@code AiModelService} 这一个端口，"回调三个 text + 一个 finished"
 * 就能把正常路径钉死；真实连通性交给 TC-03。
 *
 * @author nexus
 */
class ModelAnswerGeneratorTest {

    private static final ModelDescriptor DESCRIPTOR =
            new ModelDescriptor(ModelType.DEEPSEEK, "deepseek-chat", false, 64_000,
                    ModelDescriptor.CostTier.LOW);

    private AiModelService provider;

    private AiModelFactory modelFactory;

    private RagProperties ragProperties;

    private ModelAnswerGenerator generator;

    @BeforeEach
    void setUp() {
        provider = mock(AiModelService.class);
        modelFactory = mock(AiModelFactory.class);
        ragProperties = new RagProperties();

        when(modelFactory.provider(ModelType.DEEPSEEK)).thenReturn(provider);
        when(modelFactory.descriptor(ModelType.DEEPSEEK)).thenReturn(DESCRIPTOR);

        generator = new ModelAnswerGenerator(modelFactory, ragProperties);
    }

    @Test
    @DisplayName("★ 三个 text 分片 + 一个 finished → 按序拼成整段，finishReason 带出，模型自述来自工厂")
    void shouldAccumulateChunksInOrder() {
        stubStream(chunk -> {
            chunk.accept(AiModelService.Chunk.text("根据资料，"));
            chunk.accept(AiModelService.Chunk.text("去年净利润为 84.1 亿元"));
            chunk.accept(AiModelService.Chunk.text(" [资料1]。"));
            chunk.accept(AiModelService.Chunk.finished(ChatStreamDone.FINISH_STOP));
        });

        ModelAnswerGenerator.GeneratedAnswer answer = generator.generate(messages());

        assertEquals("根据资料，去年净利润为 84.1 亿元 [资料1]。", answer.text());
        assertEquals(ChatStreamDone.FINISH_STOP, answer.finishReason());
        assertEquals(ModelType.DEEPSEEK, answer.modelType());
        assertEquals("deepseek-chat", answer.modelName());
        assertTrue(answer.durationMs() >= 0);
    }

    @Test
    @DisplayName("空 content 的分片被跳过（'上游这一拍没吐字'不该产生空片段）")
    void shouldSkipEmptyContentChunks() {
        stubStream(chunk -> {
            chunk.accept(AiModelService.Chunk.text("甲"));
            chunk.accept(new AiModelService.Chunk("", null));
            chunk.accept(AiModelService.Chunk.text("乙"));
            chunk.accept(AiModelService.Chunk.finished(ChatStreamDone.FINISH_STOP));
        });

        assertEquals("甲乙", generator.generate(messages()).text());
    }

    @Test
    @DisplayName("finishReason=length 被如实带出（答案被 token 上限截断，是必须看得见的信号）")
    void shouldCarryLengthFinishReason() {
        stubStream(chunk -> {
            chunk.accept(AiModelService.Chunk.text("被截断的答案"));
            chunk.accept(AiModelService.Chunk.finished(ChatStreamDone.FINISH_LENGTH));
        });

        ModelAnswerGenerator.GeneratedAnswer answer = generator.generate(messages());

        assertEquals(ChatStreamDone.FINISH_LENGTH, answer.finishReason());
        assertTrue(answer.text().contains("被截断"));
    }

    @Test
    @DisplayName("★ 上游一个字都没给：不抛异常、text 为空（失败判定在 KbAskServiceImpl，见那里的 20100）")
    void shouldReturnBlankTextWithoutThrowing() {
        stubStream(chunk -> chunk.accept(AiModelService.Chunk.finished(ChatStreamDone.FINISH_STOP)));

        ModelAnswerGenerator.GeneratedAnswer answer = generator.generate(messages());

        assertTrue(answer.text().isBlank(), "本类如实返回空文本 + 记一条 warn，绝不在这里抛业务异常");
        assertEquals(ChatStreamDone.FINISH_STOP, answer.finishReason());
    }

    @Test
    @DisplayName("★ provider 抛 BusinessException(20100) → 原样上抛（不吞、不改码；出口是 HTTP 503）")
    void shouldRethrow20100Unchanged() {
        BusinessException upstream = new BusinessException(ResultCode.CHAT_UPSTREAM_UNAVAILABLE);
        doThrow(upstream).when(provider).stream(anyList(), any(), any(), any());

        BusinessException thrown = assertThrows(BusinessException.class, () -> generator.generate(messages()));

        assertSame(upstream, thrown, "必须原样上抛同一个异常实例，不做二次包装");
        assertEquals(ResultCode.CHAT_UPSTREAM_UNAVAILABLE.getCode(), thrown.getCode());
    }

    @Test
    @DisplayName("非业务异常原样上抛（落兜底 50000 —— '上游真的挂了'与'我们写挂了'必须分得开）")
    void shouldRethrowProgrammingErrorsUnchanged() {
        RuntimeException bug = new IllegalStateException("模拟程序缺陷");
        doThrow(bug).when(provider).stream(anyList(), any(), any(), any());

        RuntimeException thrown = assertThrows(RuntimeException.class, () -> generator.generate(messages()));

        assertSame(bug, thrown, "程序缺陷不得被包装成 20100");
    }

    @Test
    @DisplayName("★ answer-model-type 为空 → 系统异常（不悄悄兜一个默认模型）")
    void shouldFailFastWhenAnswerModelTypeIsBlank() {
        ragProperties.setAnswerModelType("");

        SystemException ex = assertThrows(SystemException.class, () -> generator.generate(messages()));

        assertTrue(ex.getMessage().contains("answer-model-type"),
                "文案要点名是哪个配置键，实际：" + ex.getMessage());
    }

    @Test
    @DisplayName("★ answer-model-type 写成未知取值 → 10200（两种配置错误走的是两条出口，见备注）")
    void shouldRejectUnknownAnswerModelTypeWith10200() {
        ragProperties.setAnswerModelType("XXX");

        BusinessException ex = assertThrows(BusinessException.class, () -> generator.generate(messages()));

        // ⚠️ 与实际实现一致的两条不同出口（**本轮实测**，TC-03 的备注里记了这一处不对称）：
        //    空串  → 本类自己的 SystemException（50000）
        //    未知值 → ModelType.parse 抛 BusinessException(10200,"不支持的模型类型")（HTTP 200）
        //    两者都是"配置写错了"，但用户看到的状态码不同 —— 写错配置时的一个可查性缺口，
        //    属**如实记录**的现状，不是本测试要判定的缺陷（改它要动 ModelType.parse 的既有契约）
        assertEquals(ResultCode.CHAT_MODEL_UNSUPPORTED.getCode(), ex.getCode());
    }

    @Test
    @DisplayName("传给 provider 的确实是调用方那批消息（system 指令 + user 资料/问题，顺序不变）")
    @SuppressWarnings("unchecked")
    void shouldForwardMessagesToProviderAsIs() {
        stubStream(chunk -> chunk.accept(AiModelService.Chunk.finished(ChatStreamDone.FINISH_STOP)));
        List<ChatMessage> messages = messages();

        generator.generate(messages);

        ArgumentCaptor<List<ChatMessage>> captor = ArgumentCaptor.forClass(List.class);
        verify(provider).stream(captor.capture(), any(), any(), any());
        assertSame(messages.get(0), captor.getValue().get(0), "消息对象必须原样透传（不重新构造）");
        assertEquals(2, captor.getValue().size());
    }

    @Test
    @DisplayName("本地模型：answer-model-type=OLLAMA 时取的是 OLLAMA 的实现")
    void shouldUseConfiguredProvider() {
        ragProperties.setAnswerModelType("OLLAMA");
        ModelDescriptor ollamaDescriptor =
                new ModelDescriptor(ModelType.OLLAMA, "qwen2.5:7b", false, 32_000, ModelDescriptor.CostTier.FREE);
        when(modelFactory.provider(ModelType.OLLAMA)).thenReturn(provider);
        when(modelFactory.descriptor(ModelType.OLLAMA)).thenReturn(ollamaDescriptor);
        stubStream(chunk -> {
            chunk.accept(AiModelService.Chunk.text("本地答案"));
            chunk.accept(AiModelService.Chunk.finished(ChatStreamDone.FINISH_STOP));
        });

        ModelAnswerGenerator.GeneratedAnswer answer = generator.generate(messages());

        assertEquals(ModelType.OLLAMA, answer.modelType());
        assertEquals("qwen2.5:7b", answer.modelName());
    }

    @Test
    @DisplayName("没有结束片时 finishReason 为 null（provider 违约的形态，日志里能看见）")
    void shouldLeaveFinishReasonNullWhenProviderGivesNoFinishedChunk() {
        stubStream(chunk -> chunk.accept(AiModelService.Chunk.text("没有结束片")));

        ModelAnswerGenerator.GeneratedAnswer answer = generator.generate(messages());

        assertNull(answer.finishReason());
        assertEquals("没有结束片", answer.text());
    }

    /** 造一批消息（内容不参与任何断言，本类的消费者只有 provider）。 */
    private static List<ChatMessage> messages() {
        ChatMessage system = new ChatMessage();
        system.setRole("system");
        system.setContent("你是企业知识库问答助手。");
        ChatMessage user = new ChatMessage();
        user.setRole(ChatMessage.ROLE_USER);
        user.setContent("【资料】…【问题】去年利润是多少");
        return List.of(system, user);
    }

    /** 让假 provider 在 {@code stream(...)} 时按给定剧本回调（第三个参数就是 {@code onChunk}）。 */
    @SuppressWarnings("unchecked")
    private void stubStream(Consumer<Consumer<AiModelService.Chunk>> script) {
        doAnswer(invocation -> {
            Consumer<AiModelService.Chunk> onChunk = invocation.getArgument(2);
            script.accept(onChunk);
            return null;
        }).when(provider).stream(anyList(), any(), any(), any());
    }
}
