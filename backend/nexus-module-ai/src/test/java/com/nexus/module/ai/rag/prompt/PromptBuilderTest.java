package com.nexus.module.ai.rag.prompt;

import com.nexus.module.ai.gateway.dto.ChatMessage;
import com.nexus.module.ai.rag.model.ChunkHit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PromptBuilder} 单元测试 —— <b>拼 prompt 是"检索不到就说不知道"的第三道闸</b>（设计 §4.6/§4.9）。
 *
 * <p>它防的缺陷是"prompt 拼错而答案看起来还挺对"：资料编号从 0 起（答案里会写 {@code [资料0]}）、
 * 约束句被删掉（模型开始自由发挥）、资料之间没有分隔（模型分不清哪几行属于同一份资料）——
 * 这三种都不会抛异常，只会让答案的质量静默下降。
 *
 * <p><b>0 条命中这条为什么要测</b>：本类的正常调用方（{@code KbAskServiceImpl}）在检索为空时
 * <b>根本不该调用它</b>（那是"不调大模型"的结构性保证，见 {@code KbAskServiceImplTest}）。
 * 但本类仍要能安全处理空列表 —— 它是公开 API，将来多一个调用方就可能漏掉那个短路。
 * 故此处断言的是"<b>不抛异常 + 文案非空</b>"（并会记一条 warn），而不是"这是正常路径"。
 *
 * @author nexus
 */
class PromptBuilderTest {

    @Test
    @DisplayName("3 条命中：system 约束 + user 资料/问题，编号从 [资料1] 起且与顺序一致")
    void shouldBuildSystemInstructionAndNumberedMaterials() {
        List<ChunkHit> hits = List.of(
                hit(7L, "公司年报.pdf", 11, "报告期内，公司实现营业总收入 897 亿元。", 0.8241d),
                hit(7L, "公司年报.pdf", 12, "归属于上市公司股东的净利润 84.1 亿元。", 0.7712d),
                hit(9L, "知识库说明.txt", 0, "本知识库只用于演示。", 0.6531d));

        List<ChatMessage> messages = PromptBuilder.build("去年利润是多少", hits);

        assertEquals(2, messages.size(), "两条消息：system 指令在前、user 资料/问题在后");
        ChatMessage system = messages.get(0);
        ChatMessage user = messages.get(1);

        assertEquals("system", system.getRole());
        assertEquals("user", user.getRole());
        // 第一道约束句必须在（它就是"不得使用资料之外的知识"这句话本身）
        assertTrue(system.getContent().contains("只依据【资料】回答"), "system 指令必须含约束句");
        assertTrue(system.getContent().contains("不得猜测"), "system 指令必须含'不得猜测'");

        String prompt = user.getContent();
        assertTrue(prompt.contains("【资料】"), "资料区标题");
        assertTrue(prompt.contains("【问题】去年利润是多少"), "问题原文必须原样出现在末尾");
        // 编号连续、从 1 起、顺序即入参顺序
        assertTrue(prompt.contains("[资料1] 来源：公司年报.pdf 第 12 段\n"), "编号从 1 起、段号 = chunkIndex + 1");
        assertTrue(prompt.contains("[资料2] 来源：公司年报.pdf 第 13 段\n"));
        assertTrue(prompt.contains("[资料3] 来源：知识库说明.txt 第 1 段\n"));
        assertTrue(prompt.indexOf("[资料1]") < prompt.indexOf("[资料2]")
                && prompt.indexOf("[资料2]") < prompt.indexOf("[资料3]"), "资料顺序必须是入参顺序（score 降序）");
        // 三段原文都在（引用即原文，不截断、不摘要）
        assertTrue(prompt.contains("报告期内，公司实现营业总收入 897 亿元。"));
        assertTrue(prompt.contains("归属于上市公司股东的净利润 84.1 亿元。"));
        assertFalse(prompt.contains("[资料0]"), "编号不得从 0 起（它会与答案里的引用口径对不上）");
        assertFalse(prompt.contains("[资料4]"), "3 条命中不该出现第 4 号");
    }

    @Test
    @DisplayName("1 条命中：单条资料的形状（编号仍从 1 起）")
    void shouldBuildSingleMaterial() {
        List<ChatMessage> messages = PromptBuilder.build("知识库是干什么的",
                List.of(hit(9L, "知识库说明.txt", 2, "本知识库只用于演示。", 0.71d)));

        String prompt = messages.get(1).getContent();

        assertTrue(prompt.contains("[资料1] 来源：知识库说明.txt 第 3 段\n"));
        assertFalse(prompt.contains("[资料2]"));
        assertTrue(prompt.endsWith("【问题】知识库是干什么的"), "问题必须是最后一段（模板的收尾形态）");
    }

    @Test
    @DisplayName("★ 0 条命中：不抛异常、文案非空、且不出现任何 [资料N]（调用方漏短路时也不能拼出假资料）")
    void shouldHandleZeroHitsWithoutProducingFakeMaterials() {
        List<ChatMessage> messages = PromptBuilder.build("今天杭州天气怎么样", List.of());

        assertEquals(2, messages.size());
        String prompt = messages.get(1).getContent();

        assertTrue(prompt.contains("【资料】"));
        assertTrue(prompt.contains("【问题】今天杭州天气怎么样"));
        assertFalse(prompt.contains("[资料1]"), "0 条命中时不得凭空造出一条资料");
        assertFalse(prompt.isBlank());
        // system 指令仍在（它同时是"没有资料时直接说找不到"的那条规则）
        assertTrue(messages.get(0).getContent().contains("知识库中未找到相关内容"));
    }

    @Test
    @DisplayName("null 入参与空列表同形（公开 API 不为 null 造第二套行为）")
    void shouldTreatNullHitsAsEmpty() {
        List<ChatMessage> messages = PromptBuilder.build("随便问问", null);

        assertEquals(2, messages.size());
        assertFalse(messages.get(1).getContent().contains("[资料1]"));
    }

    @Test
    @DisplayName("分块原文里的换行原样保留（引用要能对得上原文，前端靠 pre-wrap 渲染）")
    void shouldPreserveNewlinesInsideChunks() {
        String content = "第一段\n\n第二段：净利润 84.1 亿元";

        String prompt = PromptBuilder.build("利润", List.of(hit(1L, "年报.pdf", 0, content, 0.9d)))
                .get(1).getContent();

        assertTrue(prompt.contains(content), "分块内的换行必须原样进入 prompt，不能被压成一行");
    }

    /** 造一条检索命中（字段顺序即 SQL 选出的形状）。 */
    private static ChunkHit hit(long documentId, String fileName, int chunkIndex, String content, double score) {
        return new ChunkHit(documentId, fileName, chunkIndex, content, content.length(), score);
    }
}
