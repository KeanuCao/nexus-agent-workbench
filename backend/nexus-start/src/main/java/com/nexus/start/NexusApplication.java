package com.nexus.start;

import com.nexus.start.config.NexusHealthProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * nexus 统一 AI 网关演示项目 —— 启动类。
 *
 * <p>多模块扫描说明：本类位于 {@code com.nexus.start}，而各业务模块分布在
 * {@code com.nexus.common} / {@code com.nexus.infrastructure} / {@code com.nexus.module.*}，
 * 默认扫描范围（启动类所在包及子包）覆盖不到，故显式声明
 * {@code scanBasePackages = "com.nexus"} —— 多模块工程的常见必要配置。
 *
 * @author nexus
 */
@SpringBootApplication(scanBasePackages = "com.nexus")
@EnableConfigurationProperties(NexusHealthProperties.class)
public class NexusApplication {

    public static void main(String[] args) {
        // 应用启动不得依赖外部服务可用：postgres / redis / ollama 不可达时仍应正常起来，
        // 由 /api/health 的 checks 标记 DOWN（见 docs/api/README.md「启动期约束」）
        SpringApplication.run(NexusApplication.class, args);
    }
}
