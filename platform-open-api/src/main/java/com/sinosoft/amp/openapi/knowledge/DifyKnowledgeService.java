package com.sinosoft.amp.openapi.knowledge;

import com.sinosoft.amp.openapi.catalog.CatalogException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Dify 知识库实现：入库/检索代理 Dataset API，引用格式与 Simple 保持一致 [docId#chunkId]。
 */
public class DifyKnowledgeService implements KnowledgeService {

    private static final Logger log = LoggerFactory.getLogger(DifyKnowledgeService.class);

    private final DifyKnowledgeClient client;

    public DifyKnowledgeService(DifyKnowledgeClient client) {
        this.client = client;
    }

    public DifyKnowledgeService(DifyProperties properties) {
        this(new DifyKnowledgeClient(properties));
    }

    @Override
    public String providerId() {
        return "dify";
    }

    public DifyKnowledgeClient client() {
        return client;
    }

    @Override
    public SimpleKnowledgeService.IngestResult ingest(String tenantId, String title, String source, String content) {
        if (content == null || content.isBlank()) {
            throw new CatalogException("KNOWLEDGE_CONTENT_REQUIRED", "content is required");
        }
        String name = (title == null || title.isBlank()) ? "doc" : title;
        if (source != null && !source.isBlank()) {
            name = name + " (" + source + ")";
        }
        String[] created = client.createDocumentByText(name, content);
        log.info("dify ingested tenant={} documentId={} name={}", tenantId, created[0], created[1]);
        return new SimpleKnowledgeService.IngestResult(created[0], created[1], 1);
    }

    @Override
    public List<KnowledgeStore.DocRow> listDocuments(String tenantId) {
        List<KnowledgeStore.DocRow> out = new ArrayList<>();
        for (Map<String, Object> d : client.listDocuments()) {
            Object created = d.get("createdAt");
            java.time.Instant at = created instanceof Number n && n.longValue() > 0
                    ? java.time.Instant.ofEpochSecond(n.longValue())
                    : java.time.Instant.now();
            out.add(new KnowledgeStore.DocRow(
                    String.valueOf(d.get("id")),
                    tenantId,
                    String.valueOf(d.get("title")),
                    String.valueOf(d.get("source")),
                    String.valueOf(d.get("status")),
                    at));
        }
        return out;
    }

    @Override
    public List<SimpleKnowledgeService.Hit> search(String tenantId, String query, int topK) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        int k = topK <= 0 ? 5 : Math.min(topK, 20);
        List<SimpleKnowledgeService.Hit> hits = new ArrayList<>();
        for (Map<String, Object> h : client.retrieve(query, k)) {
            String documentId = String.valueOf(h.getOrDefault("documentId", ""));
            String chunkId = String.valueOf(h.getOrDefault("chunkId", ""));
            if (chunkId.isBlank() || "null".equals(chunkId)) {
                chunkId = documentId + "_0";
            }
            double score = h.get("score") instanceof Number n ? n.doubleValue() : 0.0;
            String text = String.valueOf(h.getOrDefault("text", ""));
            if (text.isBlank()) {
                continue;
            }
            hits.add(new SimpleKnowledgeService.Hit(documentId, chunkId, 0, score, text));
        }
        hits.sort(Comparator.comparingDouble(SimpleKnowledgeService.Hit::score).reversed());
        return hits.size() > k ? hits.subList(0, k) : hits;
    }

    @Override
    public String searchWithCitations(String tenantId, String query, int topK) {
        List<SimpleKnowledgeService.Hit> hits = search(tenantId, query, topK);
        if (hits.isEmpty()) {
            return "{\"query\":" + json(query) + ",\"hits\":[],\"note\":\"知识库无相关片段\",\"provider\":\"dify\"}";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("{\"query\":").append(json(query)).append(",\"provider\":\"dify\",\"hits\":[");
        for (int i = 0; i < hits.size(); i++) {
            SimpleKnowledgeService.Hit h = hits.get(i);
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"citation\":\"[").append(h.documentId()).append('#').append(h.chunkId()).append("]\"")
                    .append(",\"documentId\":").append(json(h.documentId()))
                    .append(",\"chunkId\":").append(json(h.chunkId()))
                    .append(",\"score\":").append(String.format(java.util.Locale.ROOT, "%.4f", h.score()))
                    .append(",\"text\":").append(json(h.text()))
                    .append('}');
        }
        sb.append("],\"answerHint\":\"回答时请引用片段编号，如 [")
                .append(hits.get(0).documentId())
                .append("#... ]\"}");
        return sb.toString();
    }

    private static String json(String s) {
        return simpleJson(s);
    }

    private static String simpleJson(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }
}
