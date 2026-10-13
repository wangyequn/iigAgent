package com.sinosoft.amp.openapi.knowledge;

import com.sinosoft.amp.openapi.catalog.CatalogException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 知识库模块补充：文档上传 → 解析 → 切片 → 向量化 → 检索 → 租户隔离。
 */
class KnowledgeIngestRetrievalTest {

    private SimpleKnowledgeServiceTest.InMemoryStore store;
    private SimpleKnowledgeService service;

    @BeforeEach
    void setUp() {
        store = new SimpleKnowledgeServiceTest.InMemoryStore();
        service = new SimpleKnowledgeService(store);
    }

    @Test
    void ingestFileExtractsTextAndChunks() {
        SimpleKnowledgeService.IngestResult r = service.ingestFile(
                "acme", "faq.txt",
                "什么是 HITL？HITL 即人在回路，用于危险写操作确认。".getBytes(StandardCharsets.UTF_8),
                "upload");
        assertTrue(r.chunkCount() > 0);
        assertEquals("faq.txt", r.title());
        assertFalse(store.listDocuments("acme").isEmpty());

        List<SimpleKnowledgeService.Hit> hits = service.search("acme", "HITL 危险写操作", 3);
        assertFalse(hits.isEmpty());
    }

    @Test
    void ingestRejectsBlankContent() {
        CatalogException e = assertThrows(CatalogException.class,
                () -> service.ingest("acme", "t", "src", "   "));
        assertEquals("KNOWLEDGE_CONTENT_REQUIRED", e.code());
    }

    @Test
    void longContentSplitIntoOverlappingChunks() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 100; i++) {
            sb.append("第").append(i).append("段落内容用于切片测试，知识库向量化验证。\n");
        }
        SimpleKnowledgeService.IngestResult r = service.ingest("acme", "long-doc", "test", sb.toString());
        assertTrue(r.chunkCount() > 1, "长文档应切成多片");

        List<String> chunks = SimpleKnowledgeService.split(sb.toString());
        assertEquals(r.chunkCount(), chunks.size());
        // 相邻切片有重叠
        String first = chunks.get(0);
        String second = chunks.get(1);
        String tail = first.substring(Math.max(0, first.length() - 20));
        assertTrue(second.contains(tail.substring(0, Math.min(10, tail.length()))),
                "切片应带 overlap");
    }

    @Test
    void searchTopKRespectsLimit() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 30; i++) {
            sb.append("工具 tool_").append(i).append(" 负责不同职能。");
        }
        service.ingest("acme", "tools", "test", sb.toString());
        List<SimpleKnowledgeService.Hit> hits = service.search("acme", "工具", 3);
        assertTrue(hits.size() <= 3);
    }

    @Test
    void tenantIsolationBetweenStores() {
        service.ingest("acme", "acme-doc", "test", "acme 专属知识：sinosoft 平台规范。");
        service.ingest("globex", "globex-doc", "test", "globex 专属知识：其他租户内容。");

        // acme 检索命中的片段必须全部属于 acme 的文档（hash 嵌入可能给无关节点低分）
        List<SimpleKnowledgeService.Hit> acmeHits =
                service.search("acme", "sinosoft 平台规范", 5);
        assertFalse(acmeHits.isEmpty());
        acmeHits.forEach(h -> assertEquals("acme",
                store.findDocument("acme", h.documentId()).orElseThrow().tenantId()));
        // globex 检索命中的片段同样只允许来自 globex 文档
        service.search("globex", "sinosoft 平台规范", 5).forEach(h ->
                assertEquals("globex",
                        store.listDocuments("globex").stream()
                                .filter(d -> d.id().equals(h.documentId()))
                                .findFirst().orElseThrow().tenantId()));
    }

    @Test
    void citationsCarryDocumentAndChunkIds() {
        service.ingest("acme", "kb", "test", "引用测试：safe_lookup 是只读查询工具。");
        String cited = service.searchWithCitations("acme", "safe_lookup 只读", 3);
        assertTrue(cited.contains("\"citation\":\"["));
        assertTrue(cited.contains("#"));
        assertTrue(cited.contains("answerHint"));
    }
}
