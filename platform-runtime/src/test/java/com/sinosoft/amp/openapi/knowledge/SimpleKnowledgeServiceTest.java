package com.sinosoft.amp.openapi.knowledge;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SimpleKnowledgeServiceTest {

    private InMemoryStore store;
    private SimpleKnowledgeService service;

    @BeforeEach
    void setUp() {
        store = new InMemoryStore();
        service = new SimpleKnowledgeService(store);
    }

    @Test
    void embedIsNormalizedAndStable() {
        double[] a = LocalHashEmbedder.embed("dangerous_write 写入 target amount");
        double[] b = LocalHashEmbedder.embed("dangerous_write 写入 target amount");
        assertEquals(LocalHashEmbedder.DIM, a.length);
        assertEquals(1.0, LocalHashEmbedder.cosine(a, b), 1e-6);
    }

    @Test
    void ingestSplitsAndSearchFindsQuotedText() {
        service.ingest("acme", "使用规范", "test",
                "写入必须使用 dangerous_write 工具。amount 为正整数。\n\n查询使用 safe_lookup 只读工具。");
        List<SimpleKnowledgeService.Hit> hits = service.search("acme", "dangerous_write 写入", 3);
        assertFalse(hits.isEmpty());
        assertTrue(hits.get(0).text().contains("dangerous_write"));
        String cited = service.searchWithCitations("acme", "dangerous_write 写入", 3);
        assertTrue(cited.contains("citation"));
        assertTrue(cited.contains("#"));
    }

    @Test
    void searchEmptyWhenNoDocs() {
        assertTrue(service.search("acme", "anything", 5).isEmpty());
    }

    @Test
    void toolsetWrapsCitations() {
        service.ingest("acme", "kb", "t", "HITL 确认用于危险写操作 dangerous_write。");
        KnowledgeToolset tools = new KnowledgeToolset(service, "acme");
        String out = tools.knowledgeSearch("HITL dangerous_write", 3);
        assertTrue(out.contains("citation") || out.contains("hits"));
        assertTrue(out.contains("dangerous_write") || out.contains("HITL"));
    }

    @Test
    void searchUsesPgvectorWhenEnabled() {
        VectorAwareStore vectorStore = new VectorAwareStore();
        SimpleKnowledgeService pg = new SimpleKnowledgeService(vectorStore, new LocalHashTextEmbedder(), true);
        pg.ingest("acme", "使用规范", "test", "下单必须使用 dangerous_write 工具。");
        List<SimpleKnowledgeService.Hit> hits = pg.search("acme", "dangerous_write 写入", 3);
        assertFalse(hits.isEmpty());
        assertTrue(vectorStore.vectorSearchCalls > 0);
        assertTrue(hits.get(0).text().contains("dangerous_write"));
    }

    @Test
    void searchFallsBackWhenPgvectorEmpty() {
        VectorAwareStore vectorStore = new VectorAwareStore();
        SimpleKnowledgeService pg = new SimpleKnowledgeService(vectorStore, new LocalHashTextEmbedder(), true);
        pg.ingest("acme", "使用规范", "test", "下单必须使用 dangerous_write 工具。");
        vectorStore.vectorRows.clear();
        List<SimpleKnowledgeService.Hit> hits = pg.search("acme", "dangerous_write 写入", 3);
        assertFalse(hits.isEmpty());
        assertTrue(hits.get(0).text().contains("dangerous_write"));
    }

    static class InMemoryStore implements KnowledgeStore {
        private final Map<String, DocRow> docs = new java.util.HashMap<>();
        private final List<ChunkRow> chunks = new java.util.ArrayList<>();

        @Override
        public void insertDocument(String id, String tenantId, String title, String source, java.time.Instant createdAt) {
            docs.put(id, new DocRow(id, tenantId, title, source, "ACTIVE", createdAt));
        }

        @Override
        public void insertChunk(String id, String documentId, String tenantId, int chunkIndex, String content, String embeddingJson) {
            chunks.add(new ChunkRow(id, documentId, chunkIndex, content, embeddingJson));
        }

        @Override
        public List<String> listChunkEmbeddings(String tenantId) {
            return listChunks(tenantId).stream().map(ChunkRow::embedding).toList();
        }

        @Override
        public List<ChunkRow> listChunks(String tenantId) {
            return chunks.stream().filter(c -> docs.get(c.documentId()) != null
                    && docs.get(c.documentId()).tenantId().equals(tenantId)).toList();
        }

        @Override
        public Optional<DocRow> findDocument(String tenantId, String id) {
            DocRow d = docs.get(id);
            return d != null && d.tenantId().equals(tenantId) ? Optional.of(d) : Optional.empty();
        }

        @Override
        public List<DocRow> listDocuments(String tenantId) {
            return docs.values().stream().filter(d -> d.tenantId().equals(tenantId)).toList();
        }
    }

    /** 记录 searchByVector 调用；vectorRows 非空时返回它们，否则回退 JSON。 */
    static class VectorAwareStore extends InMemoryStore {
        final List<ChunkRow> vectorRows = new java.util.ArrayList<>();
        int vectorSearchCalls;

        @Override
        public void insertChunk(String id, String documentId, String tenantId, int chunkIndex, String content, String embeddingJson) {
            super.insertChunk(id, documentId, tenantId, chunkIndex, content, embeddingJson);
            vectorRows.add(new ChunkRow(id, documentId, chunkIndex, content, embeddingJson));
        }

        @Override
        public List<ChunkRow> searchByVector(String tenantId, String queryVecLiteral, int topK) {
            vectorSearchCalls++;
            return vectorRows.stream().limit(topK).toList();
        }
    }
}
