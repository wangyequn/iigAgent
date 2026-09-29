package com.sinosoft.amp.openapi.knowledge;

import org.springframework.stereotype.Component;

/**
 * 按配置选择 Embedding 实现。
 */
@Component
public class EmbeddingFactory {

    private final EmbeddingProperties properties;
    private volatile TextEmbedder cached;

    public EmbeddingFactory(EmbeddingProperties properties) {
        this.properties = properties;
    }

    public TextEmbedder get() {
        TextEmbedder e = cached;
        if (e != null) {
            return e;
        }
        synchronized (this) {
            if (cached == null) {
                cached = create();
            }
            return cached;
        }
    }

    private TextEmbedder create() {
        LocalHashTextEmbedder hash = new LocalHashTextEmbedder();
        if (properties != null && properties.httpEnabled()) {
            return new HttpTextEmbedder(
                    properties.httpBaseUrl(),
                    properties.httpApiKey(),
                    properties.httpModel(),
                    properties.httpDimensions(),
                    hash);
        }
        return hash;
    }
}
