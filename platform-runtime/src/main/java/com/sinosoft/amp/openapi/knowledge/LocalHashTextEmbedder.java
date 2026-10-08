package com.sinosoft.amp.openapi.knowledge;

/** 本地 n-gram 哈希向量（默认，无外部依赖）。 */
public final class LocalHashTextEmbedder implements TextEmbedder {

    @Override
    public double[] embed(String text) {
        return LocalHashEmbedder.embed(text);
    }

    @Override
    public String id() {
        return "local-hash-" + LocalHashEmbedder.DIM;
    }
}
