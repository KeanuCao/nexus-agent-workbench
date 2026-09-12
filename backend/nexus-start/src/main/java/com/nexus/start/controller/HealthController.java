package com.nexus.start.controller;

import com.nexus.common.result.Result;
import com.nexus.common.result.ResultCode;
import com.nexus.start.model.HealthReport;
import com.nexus.start.service.HealthService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 健康检查接口（契约见 docs/api/openapi.yaml 与 docs/api/README.md）。
 *
 * <p>路径约定：不在类上挂 {@code @RequestMapping}，完整路径写在方法注解上 ——
 * CLAUDE.md 宪法要求统一使用 {@code @GetMapping} 等组合注解，且必须显式声明
 * {@code produces} / {@code consumes}，以明确接口契约。
 *
 * @author nexus
 */
@RestController
public class HealthController {

    private static final Logger log = LoggerFactory.getLogger(HealthController.class);

    private final HealthService healthService;

    public HealthController(HealthService healthService) {
        this.healthService = healthService;
    }

    /**
     * {@code GET /api/health} —— 依赖真实连通性探测结果。
     *
     * <p>状态码语义：
     * <ul>
     *     <li>200：全部依赖 UP，{@code data.status = UP}；</li>
     *     <li>503：任一依赖 DOWN，{@code data.status = DOWN}，响应体结构不变（仍是完整 Result + 报告），
     *         前端既能按 {@code msg} 提示，也能从 {@code data.checks} 定位具体是哪个依赖挂了。</li>
     * </ul>
     *
     * @return 统一响应体包装的健康检查报告
     */
    @GetMapping(value = "/api/health", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Result<HealthReport>> health() {
        HealthReport report = healthService.check();

        if (report.allUp()) {
            return ResponseEntity.ok(Result.success(report));
        }

        String msg = ResultCode.SERVICE_UNAVAILABLE.getMsg()
                + "：" + String.join("、", report.checks().downNames());
        log.warn("健康检查：依赖不可用，返回 503 checks={}", report.checks());

        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Result.failure(ResultCode.SERVICE_UNAVAILABLE.getCode(), msg, report));
    }
}
