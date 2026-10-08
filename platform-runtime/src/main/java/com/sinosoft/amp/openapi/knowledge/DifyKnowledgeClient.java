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
import java.nio.charset.StandardCharsets;
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

    /**
     * 文件入库（PDF/DOCX/PPTX/XLSX/TXT/MD/图片等，由 Dify 解析）。
     * 返回 [documentId, name]。
     */
    public String[] createDocumentByFile(String filename, byte[] content, String displayName) {
        String datasetId = resolveDatasetId();
        if (content == null || content.length == 0) {
            throw new CatalogException("KNOWLEDGE_CONTENT_REQUIRED", "file is empty");
        }
        String name = displayName == null || displayName.isBlank() ? filename : displayName;
        String boundary = "amp-dify-" + Long.toHexString(System.nanoTime());
        byte[] multipart = buildMultipart(boundary, filename, name, content);
        String url = properties.normalizedBaseUrl() + "/datasets/" + datasetId + "/document/create-by-file";
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(120))
                    .header("Authorization", "Bearer " + properties.apiKey())
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(multipart))
                    .build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            String text = res.body() == null ? "" : res.body();
            if (res.statusCode() < 200 || res.statusCode() >= 300) {
                log.warn("dify create-by-file -> {} {}", res.statusCode(), truncate(text));
                throw new CatalogException("DIFY_API_ERROR",
                        "dify create-by-file failed: HTTP " + res.statusCode() + " " + truncate(text));
            }
            JsonNode resp = mapper.readTree(text);
            JsonNode doc = resp.path("document");
            String docId = doc.path("id").asText(null);
            if (docId == null || docId.isBlank()) {
                docId = resp.path("data").path("document").path("id").asText(null);
            }
            if (docId == null || docId.isBlank()) {
                // 文档索引 batch 返回，id 在 data[0].id
                docId = resp.path("data").path(0).path("id").asText(null);
            }
            if (docId == null || docId.isBlank()) {
                throw new CatalogException("DIFY_INGEST_FAILED", "dify create-by-file returned no document id: " + truncate(text));
            }
            String docName = doc.path("name").asText(name);
            return new String[] {docId, docName};
        } catch (CatalogException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CatalogException("DIFY_API_INTERRUPTED", "dify file upload interrupted");
        } catch (Exception e) {
            throw new CatalogException("DIFY_API_ERROR", "dify create-by-file error: " + e.getMessage());
        }
    }

    private static byte[] buildMultipart(String boundary, String filename, String displayName, byte[] content) {
        String safeName = displayName == null || displayName.isBlank() ? filename : displayName;
        if (safeName == null || safeName.isBlank()) {
            safeName = "upload.bin";
        }
        // Dify create-by-file: only file (+ optional indexing_technique) are valid form fields.
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        try {
            String dash = "--" + boundary + "\r\n";
            out.write((dash + "Content-Disposition: form-data; name=\"indexing_technique\"\r\n\r\nhigh_quality\r\n")
                    .getBytes(StandardCharsets.UTF_8));
            out.write((dash + "Content-Disposition: form-data; name=\"file\"; filename=\"" + safeName.replace("\"", "")
                    + "\"\r\nContent-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            out.write(content);
            out.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
            return out.toByteArray();
        } catch (java.io.IOException e) {
            throw new CatalogException("DIFY_API_ERROR", "build multipart failed: " + e.getMessage());
        }
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
        int k = topK <= 0 ? 5 : Math.min(topK, 20);
        String preferred = properties.searchMethod() == null || properties.searchMethod().isBlank()
                ? "hybrid_search" : properties.searchMethod();
        try {
            return doRetrieve(datasetId, query, k, preferred, Boolean.TRUE.equals(properties.rerankingEnable()));
        } catch (CatalogException primary) {
            // embedding 供应商不可用（如 siliconflow 403）时，降级到不依赖向量的方法
            String msg = String.valueOf(primary.getMessage());
            boolean embeddingBroken = msg.contains("403")
                    || msg.contains("embeddings")
                    || msg.contains("invalid_param")
                    || msg.contains("models] Bad Request");
            if (!embeddingBroken || "keyword_search".equals(preferred) || "full_text_search".equals(preferred)) {
                throw primary;
            }
            String fallback = "full_text_search".equals(preferred) ? "keyword_search" : "full_text_search";
            log.warn("dify retrieve {} failed, fallback to {}: {}", preferred, fallback, msg);
            return doRetrieve(datasetId, query, k, fallback, false);
        }
    }

    private List<Map<String, Object>> doRetrieve(String datasetId, String query, int topK, String searchMethod,
            boolean rerank) {
        ObjectNode body = mapper.createObjectNode();
        body.put("query", query);
        ObjectNode model = body.putObject("retrieval_model");
        model.put("search_method", searchMethod);
        model.put("reranking_enable", rerank);
        // Dify 校验：reranking_mode 必须是字符串（如 "reranking_model"），不能是对象；关闭时不传
        if (rerank) {
            model.put("reranking_mode", "reranking_model");
        }
        model.put("top_k", topK);
        model.put("score_threshold_enabled", Boolean.TRUE.equals(properties.scoreThresholdEnabled()));
        model.put("score_threshold", properties.scoreThreshold() == null ? 0.5 : properties.scoreThreshold());
        body.putObject("metadata_condition");

        JsonNode resp = call("POST", "/datasets/" + datasetId + "/retrieve", body);
        List<Map<String, Object>> hits = new ArrayList<>();
        JsonNode records = resp.path("records");
        if (!records.isArray()) {
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
