package com.nexus.module.ai.gateway.dto;

/**
 * {@code event: delta} 帧的 {@code data}（契约：{@code docs/api/README.md} §6.2）。
 *
 * <p>它是本接口里唯一会重复 N 次的载荷，因此刻意<b>只带一个字段</b>：
 * {@code content} 是<b>增量片段、不是累积全文</b> —— 前端做追加，不做替换
 * （写错的表现是"屏幕上永远只有最后一个字"）。
 *
 * <p>为什么不加"第几片"/"是不是最后一片"：那两个信息一个由接收方自己数（{@code done.deltaCount}），
 * 一个由终止帧表达（{@code done} / {@code error}）。塞进每一片就是给同一件事造第二个真源，
 * 而两者一旦不同步，前端没有任何依据判断该信哪个。
 *
 * <p>为什么需要这个类而不是直接发一个 {@code Map}：载荷形状是<b>契约</b>
 * （{@code data} 恒为 {@code {"content":"..."}}），用 record 把它固定在编译期，
 * 字段名与顺序都不再有"手滑写错"的余地。这一点与 {@code ChatStreamMeta} / {@code ChatStreamDone}
 * 同源，见它们的类注释。
 *
 * @param content 增量文本片段；单帧可能只有一个汉字，永不为 {@code null}、永不为空串
 *                （空串是"上游这一拍没吐字"，provider 直接跳过、不构造分片）
 * @author nexus
 */
public record ChatStreamDelta(String content) {
}
