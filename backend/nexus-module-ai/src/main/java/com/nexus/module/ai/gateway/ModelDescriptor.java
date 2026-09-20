package com.nexus.module.ai.gateway;

import java.util.Objects;

/**
 * 模型能力元数据：一个 provider 实现"是什么、能干什么、大概多少钱"的自述。
 *
 * <p>它存在的理由有两个，都不是"为了好看"：
 * <ol>
 *     <li><b>本轮就要用</b>：{@code modelName} 是 {@code meta} 帧里必须回给前端的
 *         真实模型名（{@code qwen2.5:7b} / {@code deepseek-chat}），也是验收标准 2.2-1
 *         "传 OLLAMA / DEEPSEEK 分别命中对应实现"的唯一可见证据；</li>
 *     <li><b>为"后端自动选模型"预留判据</b>（设计 §10.1 缝④）：将来按能力筛选
 *         （如"需要工具调用则只有部分模型可选"）时，判断依据就是本对象，
 *         而不是散在各处的 {@code if (type == OLLAMA)}。</li>
 * </ol>
 *
 * <p>本轮只装配 Ollama 与 DeepSeek 两条记录，字段取值由各自的 provider 实现给出
 * （模型名来自配置，能力值按上游公布的口径填）—— <b>不要</b>在实现类里另开一份常量，
 * 那会让"能力"与"实现"两处说法不一致。
 *
 * @param type          模型类型（注册表的键，必须与 provider 实现声明的一致）
 * @param modelName     上游真实模型名（如 {@code qwen2.5:7b}），会原样出现在 {@code meta.model} 里
 * @param toolCalling   是否支持工具调用（function calling）—— 设计 §10.1 缝④的能力判据之一
 * @param contextLength 上下文窗口长度（token 数），用于将来"长文本自动走云端"这类策略
 * @param costTier      成本档位
 * @author nexus
 */
public record ModelDescriptor(
        ModelType type,
        String modelName,
        boolean toolCalling,
        int contextLength,
        CostTier costTier) {

    /**
     * 紧凑构造器：把"注册表里存在一条字段为 null 的记录"挡在装配期。
     *
     * <p>这类错误若放过，表现会是运行期 {@code meta} 帧里出现 {@code "model":null}
     * ——响应仍是 200、日志没有异常，只是前端下拉与验收判据悄悄失真。
     * 在构造期炸掉是成本最低的处置。
     */
    public ModelDescriptor {
        Objects.requireNonNull(type, "type 不能为空");
        Objects.requireNonNull(modelName, "modelName 不能为空");
        Objects.requireNonNull(costTier, "costTier 不能为空");
    }

    /**
     * 成本档位（粗粒度，够策略用即可）。
     *
     * <p>刻意不写具体单价：价格与折扣随时会变，写进代码就是一份必然过期的数据；
     * 需要比较成本时，这两档的相对关系才是稳定信息。
     */
    public enum CostTier {

        /** 本地推理：无 API 计费，成本体现在本机算力与延迟上。 */
        FREE,

        /** 云端低价档：按 token 计费，但单价在同类里属于最低的一档。 */
        LOW
    }
}
