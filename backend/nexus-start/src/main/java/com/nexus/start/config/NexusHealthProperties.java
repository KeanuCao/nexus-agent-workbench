package com.nexus.start.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 健康检查配置：绑定 {@code nexus.health.*}（见 application.yml）。
 *
 * <p>默认值即容器内生产值（compose 网络中的服务名），本地开发用环境变量覆盖，
 * 例如 Windows 宿主机直跑时：{@code OLLAMA_BASE_URL=http://localhost:11434}。
 *
 * @author nexus
 */
@ConfigurationProperties(prefix = "nexus.health")
public class NexusHealthProperties {

    /** 响应体中 {@code data.service} 的取值（契约：docs/design/00-环境与部署.md §5.3）。 */
    private String serviceName = "nexus-start";

    /** 响应体中 {@code data.version} 的取值；yml 中由 Maven 资源过滤注入 pom 版本号。 */
    private String version = "0.1.0";

    /** 单个依赖探活的超时上限（毫秒），防止慢依赖把健康检查拖成"假死"。 */
    private int probeTimeoutMs = 2000;

    /** Ollama 探活配置。 */
    private final Ollama ollama = new Ollama();

    public String getServiceName() {
        return serviceName;
    }

    public void setServiceName(String serviceName) {
        this.serviceName = serviceName;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public int getProbeTimeoutMs() {
        return probeTimeoutMs;
    }

    public void setProbeTimeoutMs(int probeTimeoutMs) {
        this.probeTimeoutMs = probeTimeoutMs;
    }

    public Ollama getOllama() {
        return ollama;
    }

    /**
     * Ollama 探活参数。
     *
     * @author nexus
     */
    public static class Ollama {

        /** 基础地址，默认 compose 服务名 {@code http://ollama:11434}。 */
        private String baseUrl = "http://ollama:11434";

        /** 探活路径：Ollama 版本接口，返回即视为进程可用（不校验模型是否已拉取）。 */
        private String versionPath = "/api/version";

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getVersionPath() {
            return versionPath;
        }

        public void setVersionPath(String versionPath) {
            this.versionPath = versionPath;
        }
    }
}
