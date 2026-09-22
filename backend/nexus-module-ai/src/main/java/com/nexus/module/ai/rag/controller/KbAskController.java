package com.nexus.module.ai.rag.controller;

import com.nexus.common.result.Result;
import com.nexus.module.ai.rag.dto.KbAnswerVO;
import com.nexus.module.ai.rag.dto.KbAskRequest;
import com.nexus.module.ai.rag.service.KbAskService;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 知识库问答接口（契约：{@code docs/api/README.md} §7.4 / {@code docs/api/openapi.yaml}）。
 *
 * <h2>为什么是 {@code produces = application/json} 而不是 {@code text/event-stream}</h2>
 * 决策 D6（2026-09-22 用户拍板）：<b>同步 JSON</b>。引用片段必须先于答案可见，用流式就得给
 * 阶段2 那个已经确认的帧协议加第五种帧（{@code sources}）+ 改契约 + 改前端 {@code sse.ts}。
 * 代价是提问后等 3~10s（前端要有 loading 态），换来的是一致性：<b>本接口的每个失败点都有确定的
 * 载体与状态码</b>（§7.7）—— 不像对话接口那样"开流前用 Result、开流后用 error 帧"。
 * 改流式是一次契约变更，见设计 §10.4。
 *
 * <h2>{"@code consumes} 为什么必须显式声明</h2>
 * 缺了它 / 传错 {@code Content-Type} 时，Spring 会抛 {@code HttpMediaTypeNotSupportedException}
 * —— 那个出口（415 + {@code 40002}）的存在本身就是一次实测出来的缺陷的修复
 * （阶段2 的对话接口曾把"调用方忘带头"报成 500「系统繁忙」，见 {@code GlobalExceptionHandler} 的类注释）。
 *
 * @author nexus
 */
@RestController
public class KbAskController {

    private final KbAskService kbAskService;

    public KbAskController(KbAskService kbAskService) {
        this.kbAskService = kbAskService;
    }

    /**
     * {@code POST /api/kb/ask}：就当前租户的知识库提一个问题。
     *
     * <p>{@code @Valid} 只挡"问题为空"（{@code @NotBlank}）；问题长度与 {@code topK} 的边界在
     * 业务侧判、失败出口同样是 {@code 40001}（理由见 {@code KbAskRequest} 的类注释：
     * 契约要的是可读文案，而 Bean Validation 的默认消息是英文的）。
     *
     * <p>租户上下文直接来自 {@code TenantContext}（请求线程）—— 本链路没有工作线程，
     * 不需要像对话接口那样把登录态搬进 {@code CallerContext}（设计 §1 的红利之一）。
     *
     * @param request 请求体（{@code question} 必填；{@code topK} 可空 = 用配置值）
     * @return {@code Result<KbAnswerVO>}（答案 + 引用片段 + 检索/生成观测块）
     */
    @PostMapping(value = "/api/kb/ask",
            produces = MediaType.APPLICATION_JSON_VALUE,
            consumes = MediaType.APPLICATION_JSON_VALUE)
    public Result<KbAnswerVO> ask(@Valid @RequestBody KbAskRequest request) {
        return Result.success(kbAskService.ask(request));
    }
}
