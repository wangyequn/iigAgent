package com.sinosoft.amp.openapi.knowledge;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface KnowledgeStore {

    void insertDocument(String id, String tenantId, String title, String source, Instant createdAt);

    void insertChunk(String id, String documentId, String tenantId, int chunkIndex, String content, String embeddingJson);

    List<String> listChunkEmbeddings(String tenantId);

    List<ChunkRow> listChunks(String tenantId);

    Optional<DocRow> findDocument(String tenantId, String id);

    List<DocRow> listDocuments(String tenantId);

    /**
     * Optional pgvector nearest search on embedding_vec (cosine distance &lt;=&gt;).
     * Return empty when unavailable so callers can fall back to JSON cosine.
     */
    default List<ChunkRow> searchByVector(String tenantId, String queryVecLiteral, int topK) {
        return List.of();
    }

    record ChunkRow(String id, String documentId, int chunkIndex, String content, String embedding) {
    }

    record DocRow(String id, String tenantId, String title, String source, String status, Instant createdAt) {
    }
}
