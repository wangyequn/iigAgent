package com.sinosoft.amp.openapi.knowledge;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * RAG Embedding 配置：hash=本地 n-gram（默认，零依赖）；http=OpenAI 兼容 /v1/embeddings。
 * usePgvector/pgvector：开启后检索优先走 pgvector embedding_vec SQL，失败回退 JSON 余弦。
 */
@ConfigurationProperties(prefix = "amp.rag")
public record EmbeddingProperties(
        /** hash | http */
        String provider,
        String httpBaseUrl,
        String httpApiKey,
        String httpModel,
        Integer httpDimensions,
        /** amp.rag.use-pgvector；默认 false */
        Boolean usePgvector,
        /** 别名：amp.rag.pgvector */
        Boolean pgvector
) {
    public static final String PROVIDER_HASH = "hash";
    public static final String PROVIDER_HTTP = "http";

    public EmbeddingProperties {
        if (provider == null || provider.isBlank()) {
            provider = PROVIDER_HASH;
        }
        if (usePgvector == null) {
            usePgvector = false;
        }
        if (pgvector == null) {
            pgvector = false;
        }
    }

    public boolean httpEnabled() {
        return PROVIDER_HTTP.equalsIgnoreCase(provider)
                && httpBaseUrl != null && !httpBaseUrl.isBlank();
    }

    public boolean pgvectorEnabled() {
        return Boolean.TRUE.equals(usePgvector) || Boolean.TRUE.equals(pgvector);
    }
}
