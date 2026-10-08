package com.sinosoft.amp.openapi.memory;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Mem0 长期记忆配置（对齐 AgentScope {@code Mem0LongTermMemory}）。
 *
 * <p>api-type=platform|self-hosted；自托管 Docker 见 {@code docker-compose.mem0.yml}。
 * enabled=false 时不挂记忆中间件/工具。
 */
@ConfigurationProperties(prefix = "amp.mem0")
public record Mem0Properties(
        /** 总开关 */
        Boolean enabled,
        /** Mem0 API 根路径：自托管 http://127.0.0.1:8888 ；Platform https://api.mem0.ai */
        String baseUrl,
        /** API Key：自托管 X-API-Key / Platform Token */
        String apiKey,
        /** platform | self-hosted（默认 self-hosted） */
        String apiType,
        /** 对话前自动语义召回 */
        Boolean autoRecall,
        /** 对话后自动 record 抽取沉淀 */
        Boolean autoCapture,
        /** 召回条数提示（Mem0 内部 topK 默认 5） */
        Integer recallTopK,
        /** HTTP 超时秒 */
        Integer timeoutSec
) {
    public Mem0Properties {
        if (apiType == null || apiType.isBlank()) {
            apiType = "self-hosted";
        }
        if (autoRecall == null) {
            autoRecall = true;
        }
        if (autoCapture == null) {
            autoCapture = true;
        }
        if (recallTopK == null || recallTopK <= 0) {
            recallTopK = 5;
        }
        if (timeoutSec == null || timeoutSec <= 0) {
            timeoutSec = 60;
        }
    }

    public boolean isConfigured() {
        return Boolean.TRUE.equals(enabled)
                && baseUrl != null && !baseUrl.isBlank();
    }

    public boolean isSelfHosted() {
        return apiType == null || "self-hosted".equalsIgnoreCase(apiType);
    }

    public String normalizedBaseUrl() {
        String u = baseUrl == null ? "" : baseUrl.trim();
        while (u.endsWith("/")) {
            u = u.substring(0, u.length() - 1);
        }
        return u;
    }
}
