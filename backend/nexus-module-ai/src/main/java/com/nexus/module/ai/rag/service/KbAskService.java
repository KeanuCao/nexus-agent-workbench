package com.nexus.module.ai.rag.service;

import com.nexus.module.ai.rag.dto.KbAnswerVO;
import com.nexus.module.ai.rag.dto.KbAskRequest;

/**
 * 知识库问答端口（={@code POST /api/kb/ask}，契约：{@code docs/api/README.md} §7.4）。
 *
 * <p>链路：<b>问题向量化 → 向量检索 TopK →（应用层阈值过滤）→ 拼 Prompt → 复用阶段2 网关生成</b>
 * （设计 §4.9）。全程<b>同步</b>（决策 D6）：返回时答案已经生成完，没有 SSE、没有会话 id、没有轮询。
 *
 * @author nexus
 */
public interface KbAskService {

    /**
     * 回答一个关于当前租户知识库的问题。
     *
     * <p><b>检索不到就说不知道</b>（决策 D9 的三道闸）：阈值筛完后一条不剩时返回
     * {@code grounded=false} + 固定文案，<b>且不调用大模型</b>（省成本，也是结构上不可能胡说的保证）。
     *
     * @param request 请求（{@code question} 必填；{@code topK} 可空 = 用配置值）
     * @return 答案 + 引用片段 + 检索/生成两个观测块
     * @throws com.nexus.common.exception.BusinessException 问题为空/超长、{@code topK} 越界（40001）；
     *                                                     向量化或生成时上游不可用（20100）；
     *                                                     上游"成功但一个字都没给"也按 20100 处置
     *                                                     （理由见实现类）
     */
    KbAnswerVO ask(KbAskRequest request);
}
