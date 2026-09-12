package com.nexus.start.probe;

import com.nexus.start.config.NexusHealthProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Duration;

/**
 * Ollama 探活：请求 {@code GET /api/version}，拿到响应体即视为可用。
 *
 * <p>语义边界：只探测 "Ollama 进程是否可用"，<b>不</b>校验 {@code qwen2.5:7b} /
 * {@code nomic-embed-text} 是否已拉取完成 —— 模型就绪由 up.sh 轮询 {@code /api/tags} 判定
 * （设计文档 §2.5 / §4.3），健康检查若把模型体积纳入判据会把启动期拖成误报。
 *
 * <p>HTTP 客户端选型：Spring 6.1 的 {@link RestClient}（spring-web 自带，零额外依赖），
 * 相比 RestTemplate 是 Boot 3.x 的现代写法，且不引入 OkHttp/WebClient 等重量级依赖。
 *
 * @author nexus
 */
@Component
@Order(3)
public class OllamaProbe implements DependencyProbe {

    /** 响应体 {@code data.checks} 中的字段名。 */
    public static final String NAME = "ollama";

    private static final Logger log = LoggerFactory.getLogger(OllamaProbe.class);

    private final RestClient restClient;

    private final String versionPath;

    public OllamaProbe(NexusHealthProperties properties) {
        NexusHealthProperties.Ollama ollama = properties.getOllama();
        Duration timeout = Duration.ofMillis(properties.getProbeTimeoutMs());

        // 连接与读取超时必须显式设置：RestClient 默认无超时，Ollama 挂起会把健康检查拖死
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(timeout);
        requestFactory.setReadTimeout(timeout);

        this.restClient = RestClient.builder()
                .requestFactory(requestFactory)
                .baseUrl(ollama.getBaseUrl())
                .build();
        this.versionPath = ollama.getVersionPath();
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public DependencyStatus check() {
        try {
            String body = restClient.get()
                    .uri(versionPath)
                    .retrieve()
                    .body(String.class);

            if (body == null || body.isBlank()) {
                log.warn("健康检查：ollama 响应体为空 path={}", versionPath);
                return DependencyStatus.DOWN;
            }
            return DependencyStatus.UP;
        } catch (RestClientException ex) {
            // 连接被拒 / 超时 / 4xx / 5xx 在 RestClient 中统一收敛为 RestClientException
            log.warn("健康检查：ollama 连通性探测失败 cause={} message={}",
                    ex.getClass().getSimpleName(), ex.getMessage());
            return DependencyStatus.DOWN;
        }
    }
}
