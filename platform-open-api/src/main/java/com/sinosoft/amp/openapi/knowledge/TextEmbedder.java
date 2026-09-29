package com.sinosoft.amp.openapi.knowledge;

/**
 * 可插拔文本向量。实现方：LocalHashTextEmbedder / HttpTextEmbedder。
 */
public interface TextEmbedder {

    double[] embed(String text);

    /** 便于日志/指标区分实现。 */
    String id();
}
