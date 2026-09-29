package com.sinosoft.amp.openapi.knowledge;

import com.sinosoft.amp.openapi.catalog.CatalogException;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.rag.Knowledge;
import io.agentscope.core.rag.model.Document;
import io.agentscope.core.rag.model.DocumentMetadata;
import io.agentscope.core.rag.model.RetrieveConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * S6 Simple RAG：文档切片 → Embedder（hash/http）→ 检索（余弦）→ 带引用片段。
 * 实现 AgentScope {@link Knowledge}，可挂 KnowledgeRetrievalTools。
 */
@Service
public class SimpleKnowledgeService implements Knowledge, KnowledgeService {

    private static final Logger log = LoggerFactory.getLogger(SimpleKnowledgeService.class);
    private static final int CHUNK_SIZE = 360;
    private static final int CHUNK_OVERLAP = 40;

    private final KnowledgeStore store;
    private final TextEmbedder embedder;
    private final boolean usePgvector;

    public SimpleKnowledgeService(KnowledgeStore store) {
        this(store, new LocalHashTextEmbedder(), false);
    }

    public SimpleKnowledgeService(KnowledgeStore store, TextEmbedder embedder) {
        this(store, embedder, false);
    }

    public SimpleKnowledgeService(KnowledgeStore store, TextEmbedder embedder, boolean usePgvector) {
        this.store = store;
        this.embedder = embedder == null ? new LocalHashTextEmbedder() : embedder;
        this.usePgvector = usePgvector;
    }

    /** Spring 装配：按 amp.rag 选择 hash / http embedding；usePgvector/pgvector 优先 SQL 检索。 */
    @org.springframework.beans.factory.annotation.Autowired
    public SimpleKnowledgeService(KnowledgeStore store, EmbeddingFactory embeddingFactory, EmbeddingProperties properties) {
        this(store,
                embeddingFactory == null ? new LocalHashTextEmbedder() : embeddingFactory.get(),
                properties != null && properties.pgvectorEnabled());
    }

    public String embedderId() {
        return embedder.id();
    }

    @Override
    public String providerId() {
        return "simple";
    }

    /** 管理台上传：切片入库。 */
    public IngestResult ingest(String tenantId, String title, String source, String content) {
        if (content == null || content.isBlank()) {
            throw new CatalogException("KNOWLEDGE_CONTENT_REQUIRED", "content is required");
        }
        String docId = UUID.randomUUID().toString().replace("-", "");
        store.insertDocument(docId, tenantId, title == null ? "doc" : title, source, Instant.now());
        List<String> chunks = split(content);
        int idx = 0;
        for (String chunk : chunks) {
            String chunkId = docId + "_" + idx;
            store.insertChunk(chunkId, docId, tenantId, idx, chunk,
                    VectorCodec.toJson(embedder.embed(chunk)));
            idx++;
        }
        log.info("knowledge ingested docId={} chunks={} tenant={} embedder={}",
                docId, idx, tenantId, embedder.id());
        return new IngestResult(docId, title, idx);
    }

    public List<KnowledgeStore.DocRow> listDocuments(String tenantId) {
        return store.listDocuments(tenantId);
    }

    /**
     * 检索：返回带引用的片段列表（docId/chunkId/text/score）。
     */
    public List<Hit> search(String tenantId, String query, int topK) {
        double[] q = embedder.embed(query);
        int k = topK <= 0 ? 5 : Math.min(topK, 10);
        if (usePgvector) {
            List<Hit> vectorHits = searchByPgvector(tenantId, q, k);
            if (vectorHits != null) {
                return vectorHits;
            }
        }
        List<Hit> hits = new ArrayList<>();
        for (KnowledgeStore.ChunkRow c : store.listChunks(tenantId)) {
            double score = VectorCodec.cosine(q, VectorCodec.fromJson(c.embedding()));
            if (score > 0.01) {
                hits.add(new Hit(c.documentId(), c.id(), c.chunkIndex(), score, c.content()));
            }
        }
        hits.sort(Comparator.comparingDouble(Hit::score).reversed());
        return hits.stream().limit(k).toList();
    }

    /** pgvector SQL 路径；null 表示不可用/无结果，由调用方回退 JSON 余弦。 */
    private List<Hit> searchByPgvector(String tenantId, double[] q, int k) {
        try {
            List<KnowledgeStore.ChunkRow> rows = store.searchByVector(tenantId, VectorCodec.toJson(q), k);
            if (rows == null || rows.isEmpty()) {
                return null;
            }
            List<Hit> hits = new ArrayList<>();
            for (KnowledgeStore.ChunkRow c : rows) {
                double score = VectorCodec.cosine(q, VectorCodec.fromJson(c.embedding()));
                hits.add(new Hit(c.documentId(), c.id(), c.chunkIndex(), score, c.content()));
            }
            hits.sort(Comparator.comparingDouble(Hit::score).reversed());
            return hits.stream().limit(k).toList();
        } catch (RuntimeException ex) {
            log.warn("pgvector search unavailable, fallback to JSON cosine: {}", ex.toString());
            return null;
        }
    }

    /** 引用格式：便于模型「引用原文片段」。 */
    public String searchWithCitations(String tenantId, String query, int topK) {
        List<Hit> hits = search(tenantId, query, topK);
        if (hits.isEmpty()) {
            return "{\"query\":" + json(query) + ",\"hits\":[],\"note\":\"知识库无相关片段\"}";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("{\"query\":").append(json(query)).append(",\"hits\":[");
        for (int i = 0; i < hits.size(); i++) {
            Hit h = hits.get(i);
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
        sb.append("],\"answerHint\":\"回答时请引用片段编号，如 [").append(hits.get(0).documentId())
                .append("#... ]\"}");
        return sb.toString();
    }

    /** AgentScope Knowledge SPI（进程内/测试可直接 addDocuments）。 */
    @Override
    public Mono<Void> addDocuments(List<Document> documents) {
        return Mono.fromRunnable(() -> {
            if (documents == null) {
                return;
            }
            for (Document d : documents) {
                DocumentMetadata md = d.getMetadata();
                String tenant = String.valueOf(md.getPayloadValue("tenantId"));
                if ("null".equals(tenant)) {
                    tenant = "acme";
                }
                String title = String.valueOf(md.getPayloadValue("title"));
                if ("null".equals(title)) {
                    title = md.getDocId();
                }
                String text = md.getContentText();
                double[] emb = d.getEmbedding() != null ? d.getEmbedding() : embedder.embed(text);
                store.insertDocument(md.getDocId(), tenant, title, "api", Instant.now());
                store.insertChunk(md.getChunkId(), md.getDocId(), tenant, 0, text, VectorCodec.toJson(emb));
            }
        });
    }

    @Override
    public Mono<List<Document>> retrieve(String query, RetrieveConfig config) {
        return Mono.fromCallable(() -> {
            String tenant = System.getProperty("amp.default.tenant", "acme");
            int topK = config == null ? 5 : config.getLimit();
            List<Document> out = new ArrayList<>();
            for (Hit h : search(tenant, query, topK)) {
                DocumentMetadata md = DocumentMetadata.builder()
                        .content(TextBlock.builder().text(h.text()).build())
                        .docId(h.documentId())
                        .chunkId(h.chunkId())
                        .addPayload("score", h.score())
                        .addPayload("citation", "[" + h.documentId() + "#" + h.chunkId() + "]")
                        .build();
                Document doc = new Document(md);
                doc.setScore(h.score());
                out.add(doc);
            }
            return out;
        });
    }

    static List<String> split(String content) {
        String normalized = content.replace("\r\n", "\n").trim();
        List<String> out = new ArrayList<>();
        int i = 0;
        while (i < normalized.length()) {
            int end = Math.min(i + CHUNK_SIZE, normalized.length());
            if (end < normalized.length()) {
                int cut = normalized.lastIndexOf('\n', end);
                if (cut > i + CHUNK_SIZE / 2) {
                    end = cut;
                }
            }
            out.add(normalized.substring(i, end).trim());
            if (end >= normalized.length()) {
                break;
            }
            i = Math.max(end - CHUNK_OVERLAP, i + 1);
        }
        return out.stream().filter(s -> !s.isBlank()).toList();
    }

    private static String json(String s) {
        if (s == null) {
            return "null";
        }
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "").replace("\t", "\\t") + "\"";
    }

    public record IngestResult(String documentId, String title, int chunkCount) {
    }

    public record Hit(String documentId, String chunkId, int chunkIndex, double score, String text) {
    }
}
