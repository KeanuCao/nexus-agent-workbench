package com.nexus.module.ai.rag.prompt;

import com.nexus.module.ai.gateway.dto.ChatMessage;
import com.nexus.module.ai.rag.model.ChunkHit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * 提问 + 检索片段 → 两条消息（{@code system} 指令 + {@code user} 资料/问题），设计 §4.6 的模板逐字实现。
 *
 * <p><b>无状态纯函数</b>：{@code final} 类 + 私有构造 + 静态方法，刻意不做成 Spring bean ——
 * 它不依赖任何配置与外部资源，做成 bean 只会给单测平白加一个启动上下文的负担
 * （与 {@code ModelType} 这类工具类同一取舍）。
 *
 * <h2>三条写进注释的边界</h2>
 * <ol>
 *     <li><b>为什么 {@code system} 消息是合法的</b>：契约 §6.1 的"不支持 {@code system}"是
 *         <b>对话接口入参</b>的约定（Bean Validation 在控制器上生效），不是 provider 的能力限制
 *         —— 两个上游都接受 {@code system}，且本调用不经那层校验。
 *         （{@code ChatMessage} 里只有 {@code ROLE_USER}/{@code ROLE_ASSISTANT} 两个常量，
 *         本类的 {@code system} 是字面量：那是<b>上行</b>的角色，与入参 DTO 的取值域不是一回事。
 *         若上游真的拒收 {@code system}（会以 20100 现形），退路是把系统指令并入唯一一条 user 消息
 *         —— 一个开关的事，见设计 §4.6 的"待实测确认"）；</li>
 *     <li><b>答案长度约束只写在 prompt 里</b>（"不超过 200 字"），不做后端校验：
 *         那是模型的行为要求，不是接口的规则 —— 写进契约就成了后端必须校验的约束，而它拦不住模型
 *         （设计 §3.6 的备注）；</li>
 *     <li><b>0 条命中时本类不该被调用</b>：检索为空短路不调大模型，是"检索不到就说不知道"的
 *         <b>结构性保证</b>（设计 §4.9 第 1 条）。本类仍能处理空列表（不抛异常、文案非空），
 *         但会记一条 warn —— 因为"带着空资料去问模型"正是那个保证失效的形态，
 *         它必须在日志里可见，而不是悄悄产生一个看着像回答的幻觉。</li>
 * </ol>
 *
 * <h2>不打印正文</h2>
 * 本类不打任何正文日志（资料片段与问题都属用户数据，设计 §4.11）。
 *
 * @author nexus
 */
public final class PromptBuilder {

    private static final Logger log = LoggerFactory.getLogger(PromptBuilder.class);

    /**
     * 系统角色。写成字面量而不是引用 {@code ChatMessage.ROLE_SYSTEM}（那个常量不存在，
     * 且不该存在 —— 见类注释第 1 条）。
     */
    private static final String ROLE_SYSTEM = "system";

    /** 用户角色，取自入参 DTO 的常量（它与上行取值本来就是同一个 {@code user}）。 */
    private static final String ROLE_USER = ChatMessage.ROLE_USER;

    /** 系统指令（设计 §4.6 逐字），四条规则一一对应"检索不到就说不知道"的第三道闸。 */
    private static final String SYSTEM_INSTRUCTION = """
            你是企业知识库问答助手。规则：
            1) 只依据【资料】回答，不得使用资料之外的知识，不得猜测；
            2) 若【资料】中没有答案，直接回答「知识库中未找到相关内容」；
            3) 引用来源时在句末标注 [资料N]，N 是资料编号；
            4) 使用简体中文，简洁作答（不超过 200 字）。""";

    /** 资料区标题。 */
    private static final String MATERIALS_HEADER = "【资料】";

    /** 问题区前缀（与问题之间空一行，见模板）。 */
    private static final String QUESTION_PREFIX = "\n\n【问题】";

    /** 资料条目的来源行前缀，形如 {@code [资料1] 来源：}。 */
    private static final String SOURCE_LABEL_PREFIX = "[资料";

    /** 私有构造：纯函数类不需要实例。 */
    private PromptBuilder() {
    }

    /**
     * 按模板拼装两条消息。
     *
     * <p>产出形状（设计 §4.6）：
     * <pre>
     * system: 你是企业知识库问答助手。规则：…
     * user:  【资料】
     *        [资料1] 来源：公司年报.pdf 第 12 段
     *        &lt;分块原文&gt;
     *
     *        [资料2] 来源：公司年报.pdf 第 13 段
     *        &lt;分块原文&gt;
     *
     *        【问题】去年利润是多少
     * </pre>
     *
     * @param question 用户问题原文（已由控制器校验：去空白后非空、长度 ≤ 500）
     * @param hits     检索命中片段（已按 score 降序、已过阈值），可为空列表
     * @return 两条消息（system 在前、user 在后），可直接交给 {@code AiModelService.stream(...)}
     */
    public static List<ChatMessage> build(String question, List<ChunkHit> hits) {
        List<ChunkHit> materials = hits == null ? List.of() : hits;
        if (materials.isEmpty()) {
            log.warn("PromptBuilder 收到 0 条命中 —— 调用方本应短路（设计 §4.9：检索为空不得调用大模型），"
                    + "否则等于让模型在无资料的条件下自由发挥");
        }

        List<ChatMessage> messages = new ArrayList<>(2);
        messages.add(message(ROLE_SYSTEM, SYSTEM_INSTRUCTION));
        messages.add(message(ROLE_USER, buildUserPrompt(question, materials)));
        return messages;
    }

    /**
     * 拼 user 消息：资料清单 + 问题。
     *
     * @param question  用户问题
     * @param materials 命中片段（按 score 降序）
     * @return user 消息正文
     */
    private static String buildUserPrompt(String question, List<ChunkHit> materials) {
        StringBuilder builder = new StringBuilder();
        builder.append(MATERIALS_HEADER);
        for (int i = 0; i < materials.size(); i++) {
            ChunkHit hit = materials.get(i);
            // 首条紧跟标题换一行；其后每条之前空一行 —— 模型靠这个分隔读清"哪几行属于同一份资料"
            builder.append(i == 0 ? "\n" : "\n\n");
            builder.append(SOURCE_LABEL_PREFIX)
                    // 编号从 1 起（[资料1] 而不是 [资料0]）：与答案里 [资料N] 的引用口径一致
                    .append(i + 1)
                    .append("] 来源：")
                    .append(hit.fileName())
                    // chunk_index 是 0 起的存储口径，展示给人看要 +1（契约 §7.6 同一口径）
                    .append(" 第 ")
                    .append(hit.chunkIndex() + 1)
                    .append(" 段\n")
                    // 原文原样拼接：引用即原文，换行是结构信息（前端要 pre-wrap 渲染的同一个理由）
                    .append(hit.content());
        }
        builder.append(QUESTION_PREFIX).append(question);
        return builder.toString();
    }

    /**
     * 构造一条上行消息。
     *
     * <p>用可变 POJO {@link ChatMessage} 而不是自定义 record：它就是上行消息的类型
     * （网关的 {@code UpstreamChatRequest} 会把它转成真正的报文），在此另造一个等价类型
     * 只会多一处需要同步的映射。
     *
     * @param role    角色
     * @param content 正文
     * @return 消息对象
     */
    private static ChatMessage message(String role, String content) {
        ChatMessage message = new ChatMessage();
        message.setRole(role);
        message.setContent(content);
        return message;
    }
}
