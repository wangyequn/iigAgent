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
        double[] a = LocalHashEmbedder.embed("place_order 下单 product quantity");
        double[] b = LocalHashEmbedder.embed("place_order 下单 product quantity");
        assertEquals(LocalHashEmbedder.DIM, a.length);
        assertEquals(1.0, LocalHashEmbedder.cosine(a, b), 1e-6);
    }

    @Test
    void ingestSplitsAndSearchFindsQuotedText() {
        service.ingest("acme", "订单规范", "test",
                "下单必须使用 place_order 工具。quantity 为正整数。\n\n查询使用 query_orders 只读工具。");
        List<SimpleKnowledgeService.Hit> hits = service.search("acme", "place_order 下单", 3);
        assertFalse(hits.isEmpty());
        assertTrue(hits.get(0).text().contains("place_order"));
        String cited = service.searchWithCitations("acme", "place_order 下单", 3);
        assertTrue(cited.contains("citation"));
        assertTrue(cited.contains("#"));
    }

    @Test
    void searchEmptyWhenNoDocs() {
        assertTrue(service.search("acme", "anything", 5).isEmpty());
    }

    @Test
    void toolsetWrapsCitations() {
        service.ingest("acme", "kb", "t", "HITL 确认用于危险写操作 place_order。");
        KnowledgeToolset tools = new KnowledgeToolset(service, "acme");
        String out = tools.knowledgeSearch("HITL place_order", 3);
        assertTrue(out.contains("citation") || out.contains("hits"));
        assertTrue(out.contains("place_order") || out.contains("HITL"));
    }

    @Test
    void searchUsesPgvectorWhenEnabled() {
        VectorAwareStore vectorStore = new VectorAwareStore();
        SimpleKnowledgeService pg = new SimpleKnowledgeService(vectorStore, new LocalHashTextEmbedder(), true);
        pg.ingest("acme", "订单规范", "test", "下单必须使用 place_order 工具。");
        List<SimpleKnowledgeService.Hit> hits = pg.search("acme", "place_order 下单", 3);
        assertFalse(hits.isEmpty());
        assertTrue(vectorStore.vectorSearchCalls > 0);
        assertTrue(hits.get(0).text().contains("place_order"));
    }

    @Test
    void searchFallsBackWhenPgvectorEmpty() {
        VectorAwareStore vectorStore = new VectorAwareStore();
        SimpleKnowledgeService pg = new SimpleKnowledgeService(vectorStore, new LocalHashTextEmbedder(), true);
        pg.ingest("acme", "订单规范", "test", "下单必须使用 place_order 工具。");
        vectorStore.vectorRows.clear();
        List<SimpleKnowledgeService.Hit> hits = pg.search("acme", "place_order 下单", 3);
        assertFalse(hits.isEmpty());
        assertTrue(hits.get(0).text().contains("place_order"));
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
