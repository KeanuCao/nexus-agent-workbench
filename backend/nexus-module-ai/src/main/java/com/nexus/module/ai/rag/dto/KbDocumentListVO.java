package com.nexus.module.ai.rag.dto;

import java.util.List;

/**
 * 文档列表响应（契约：{@code docs/api/README.md} §7.2）。
 *
 * <h2>{@code total} 本轮与 {@code items.length} 恒等，为什么还留着它</h2>
 * 因为它是<b>分页的前置形状</b>：将来加 {@code page}/{@code size} 时，契约只需给这两个参数加语义，
 * 响应结构不必改（前端也不必改解包代码）。现在就撤掉它，将来加回来是一次契约变更 ——
 * 一个字段的成本远低于一次变更（设计 §3.2 的同一取舍）。
 *
 * <p>⚠️ 本轮<b>不保证</b> {@code items} 的顺序（契约 §7.2 明说前端不要依赖）。实现里仍固定按
 * 文档 ID 倒序给出，是为了让列表在刷新前后稳定 —— 那是"额外的好意"，不是契约承诺，
 * 前端不要据此写逻辑。
 *
 * @param items 当前租户的文档列表（可为空列表，不是 {@code null}）
 * @param total 总数（本轮 = {@code items.size()}）
 * @author nexus
 */
public record KbDocumentListVO(List<KbDocumentVO> items, int total) {

    /**
     * 由列表项构造（{@code total} 自动取条数，避免两个字段各写一次而写不一致）。
     *
     * @param items 列表项（非 {@code null}）
     * @return 列表响应
     */
    public static KbDocumentListVO of(List<KbDocumentVO> items) {
        return new KbDocumentListVO(items, items.size());
    }
}
