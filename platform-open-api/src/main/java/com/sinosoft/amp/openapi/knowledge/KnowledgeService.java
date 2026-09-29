package com.sinosoft.amp.openapi.knowledge;

/**
 * 平台知识服务 SPI：Simple（自建 PG）与 Dify 等外部知识库统一入口。
 * DTO 沿用 {@code SimpleKnowledgeService.Hit / IngestResult}，避免全链路类型漂移。
 */
public interface KnowledgeService {

    String providerId();

    SimpleKnowledgeService.IngestResult ingest(String tenantId, String title, String source, String content);

    java.util.List<KnowledgeStore.DocRow> listDocuments(String tenantId);

    java.util.List<SimpleKnowledgeService.Hit> search(String tenantId, String query, int topK);

    String searchWithCitations(String tenantId, String query, int topK);
}
