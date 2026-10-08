package com.sinosoft.amp.openapi.knowledge;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Dify 知识库（Dataset API）配置。
 * <p>
 * 典型值：base-url=http://192.168.31.201/v1，api-key=dataset-xxxx。
 * dataset-id 留空时按 dataset-name 自动查找/创建。
 */
@ConfigurationProperties(prefix = "amp.rag.dify")
public record DifyProperties(
        /** 总开关：true 时知识读写走 Dify */
        Boolean enabled,
        /** Dify Knowledge API 根路径，通常以 /v1 结尾 */
        String baseUrl,
        /** Dataset API Key（dataset-...） */
        String apiKey,
        /** 可选；不填则用 datasetName 自动解析 */
        String datasetId,
        /** 自动创建/匹配的数据集名称 */
        String datasetName,
        /** 检索方式：hybrid_search / semantic_search / full_text_search / keyword_search */
        String searchMethod,
        /** 重排序开关（需 Dify 侧配置 rerank 模型） */
        Boolean rerankingEnable,
        /** 评分阈值开关 */
        Boolean scoreThresholdEnabled,
        Double scoreThreshold
) {
    public DifyProperties {
        if (datasetName == null || datasetName.isBlank()) {
            datasetName = "amp-knowledge";
        }
        if (searchMethod == null || searchMethod.isBlank()) {
            searchMethod = "hybrid_search";
        }
        if (rerankingEnable == null) {
            rerankingEnable = false;
        }
        if (scoreThresholdEnabled == null) {
            scoreThresholdEnabled = false;
        }
    }

    public boolean isConfigured() {
        return Boolean.TRUE.equals(enabled)
                && baseUrl != null && !baseUrl.isBlank()
                && apiKey != null && !apiKey.isBlank();
    }

    /** 归一化 base：去掉尾部 /，保证 + "/datasets" 正确。 */
    public String normalizedBaseUrl() {
        String u = baseUrl == null ? "" : baseUrl.trim();
        while (u.endsWith("/")) {
            u = u.substring(0, u.length() - 1);
        }
        return u;
    }
}
