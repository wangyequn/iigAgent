package com.sinosoft.amp.openapi.knowledge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sinosoft.amp.openapi.catalog.CatalogException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Dify 知识库 Dataset API 客户端（文本入库 / 检索 / 文档列表）。
 */
public class DifyKnowledgeClient {

    private static final Logger log = LoggerFactory.getLogger(DifyKnowledgeClient.class);

    private final DifyProperties properties;
    private final HttpClient http;
    private final ObjectMapper mapper;
    private volatile String cachedDatasetId;

    public DifyKnowledgeClient(DifyProperties properties) {
        this(properties, HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build(),
                new ObjectMapper());
    }

    public DifyKnowledgeClient(DifyProperties properties, HttpClient http, ObjectMapper mapper) {
        this.properties = properties;
        this.http = http;
        this.mapper = mapper;
    }

    public DifyProperties properties() {
        return properties;
    }

    public String resolveDatasetId() {
        if (!properties.isConfigured()) {
            throw new CatalogException("DIFY_NOT_CONFIGURED", "Dify knowledge base is not configured");
        }
        if (properties.datasetId() != null && !properties.datasetId().isBlank()) {
            return properties.datasetId().trim();
        }
        String cached = cachedDatasetId;
        if (cached != null) {
            return cached;
        }
        synchronized (this) {
            if (cachedDatasetId != null) {
                return cachedDatasetId;
            }
            String id = findOrCreateDataset(properties.datasetName());
            cachedDatasetId = id;
            log.info("dify dataset resolved id={} name={}", id, properties.datasetName());
            return id;
        }
    }

    private String findOrCreateDataset(String name) {
        JsonNode list = call("GET", "/datasets", null);
        JsonNode data = list.path("data");
        if (data.isArray()) {
            for (JsonNode ds : data) {
                if (name.equals(ds.path("name").asText())) {
                    return ds.path("id").asText();
                }
            }
        }
        ObjectNode body = mapper.createObjectNode();
        body.put("name", name);
        body.put("permission", "all_team_members");
        JsonNode created = call("POST", "/datasets", body);
        String id = created.path("id").asText(null);
        if (id == null || id.isBlank()) {
            throw new CatalogException("DIFY_DATASET_CREATE_FAILED", "failed to create dify dataset: " + name);
        }
        return id;
    }

    /** 文本入库。返回 [documentId, name]。 */
    public String[] createDocumentByText(String name, String text) {
        String datasetId = resolveDatasetId();
        ObjectNode body = mapper.createObjectNode();
        body.put("name", name == null || name.isBlank() ? "doc" : name);
        body.put("text", text);
        body.put("indexing_technique", "high_quality");
        ObjectNode process = body.putObject("process_rule");
        process.put("mode", "automatic");
        JsonNode resp = call("POST", "/datasets/" + datasetId + "/document/create-by-text", body);
        JsonNode doc = resp.path("document");
        String docId = doc.path("id").asText(null);
        if (docId == null || docId.isBlank()) {
            // 兼容部分版本返回 data.document
            docId = resp.path("data").path("document").path("id").asText(null);
        }
        if (docId == null || docId.isBlank()) {
            throw new CatalogException("DIFY_INGEST_FAILED", "dify create-by-text returned no document id: " + resp);
        }
        String docName = doc.path("name").asText(name);
        return new String[] {docId, docName};
    }

    public List<Map<String, Object>> listDocuments() {
        String datasetId = resolveDatasetId();
        JsonNode resp = call("GET", "/datasets/" + datasetId + "/documents?page=1&limit=100", null);
        List<Map<String, Object>> out = new ArrayList<>();
        JsonNode data = resp.path("data");
        if (data.isArray()) {
            for (JsonNode d : data) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", d.path("id").asText());
                m.put("title", d.path("name").asText());
                m.put("source", "dify");
                m.put("status", d.path("indexing_status").asText("unknown"));
                m.put("createdAt", d.path("created_at").asLong(0L));
                out.add(m);
            }
        }
        return out;
    }

    public List<Map<String, Object>> retrieve(String query, int topK) {
        String datasetId = resolveDatasetId();
        ObjectNode body = mapper.createObjectNode();
        body.put("query", query);
        ObjectNode model = body.putObject("retrieval_model");
        model.put("search_method", properties.searchMethod());
        model.put("reranking_enable", Boolean.TRUE.equals(properties.rerankingEnable()));
        model.putObject("reranking_mode");
        model.put("top_k", topK <= 0 ? 5 : Math.min(topK, 20));
        model.put("score_threshold_enabled", Boolean.TRUE.equals(properties.scoreThresholdEnabled()));
        model.put("score_threshold", properties.scoreThreshold() == null ? 0.5 : properties.scoreThreshold());
        body.putObject("metadata_condition");

        JsonNode resp = call("POST", "/datasets/" + datasetId + "/retrieve", body);
        List<Map<String, Object>> hits = new ArrayList<>();
        JsonNode records = resp.path("records");
        if (!records.isArray()) {
            // 个别版本字段为 hits / segments
            records = resp.path("hits");
        }
        if (records.isArray()) {
            for (JsonNode r : records) {
                JsonNode seg = r.has("segment") ? r.path("segment") : r;
                Map<String, Object> hit = new LinkedHashMap<>();
                String content = seg.path("content").asText("");
                String documentId = seg.path("document_id").asText(seg.path("document").path("id").asText(""));
                String documentName = seg.path("document").path("name").asText("");
                String segmentId = seg.path("id").asText("");
                double score = seg.path("score").asDouble(r.path("score").asDouble(0.0));
                hit.put("documentId", documentId);
                hit.put("chunkId", segmentId);
                hit.put("documentName", documentName);
                hit.put("score", score);
                hit.put("text", content);
                hits.add(hit);
            }
        }
        return hits;
    }

    private JsonNode call(String method, String path, JsonNode body) {
        String url = properties.normalizedBaseUrl() + path;
        try {
            HttpRequest.Builder rb = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(30))
                    .header("Authorization", "Bearer " + properties.apiKey())
                    .header("Content-Type", "application/json");
            if (body == null || "GET".equalsIgnoreCase(method)) {
                rb.method(method, HttpRequest.BodyPublishers.noBody());
            } else {
                rb.method(method, HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
            }
            HttpResponse<String> res = http.send(rb.build(), HttpResponse.BodyHandlers.ofString());
            int code = res.statusCode();
            String text = res.body() == null ? "" : res.body();
            if (code < 200 || code >= 300) {
                log.warn("dify api {} {} -> {} {}", method, path, code, truncate(text));
                throw new CatalogException("DIFY_API_ERROR", "dify " + method + " " + path + " failed: HTTP " + code + " " + truncate(text));
            }
            if (text.isBlank()) {
                return mapper.createObjectNode();
            }
            return mapper.readTree(text);
        } catch (CatalogException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CatalogException("DIFY_API_INTERRUPTED", "dify api interrupted");
        } catch (Exception e) {
            log.warn("dify api {} {} error: {}", method, path, e.toString());
            throw new CatalogException("DIFY_API_ERROR", "dify " + method + " " + path + " error: " + e.getMessage());
        }
    }

    private static String truncate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() > 240 ? s.substring(0, 240) + "..." : s;
    }
}
