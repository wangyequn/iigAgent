package com.sinosoft.amp.openapi.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * Mem0 管理面 REST（list/get/delete/search）。
 *
 * <p>对话读写走官方 {@code Mem0LongTermMemory}；本类只服务 Admin API，
 * 因官方扩展未暴露 CRUD。鉴权同官方客户端：self-hosted 用 {@code X-API-Key}。
 */
public class Mem0AdminClient {

    private static final Logger log = LoggerFactory.getLogger(Mem0AdminClient.class);

    private final Mem0Properties properties;
    private final HttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();

    public Mem0AdminClient(Mem0Properties properties) {
        this(properties, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
    }

    public Mem0AdminClient(Mem0Properties properties, HttpClient http) {
        this.properties = properties;
        this.http = http;
    }

    public Mem0Properties properties() {
        return properties;
    }

    public List<Map<String, Object>> list(String mem0UserId, String agentId, String runId, int limit) {
        // Mem0 过滤必须带 user_id/agent_id/run_id 之一；优先 user，其次 agent，再 run
        String uid = mem0UserId;
        if (!notBlank(uid) && !notBlank(agentId) && !notBlank(runId)) {
            throw new CatalogException("MEMORY_INVALID",
                    "userId, agentId or sessionId is required for list");
        }
        StringBuilder path = new StringBuilder("/memories?");
        if (notBlank(uid)) {
            path.append("user_id=").append(enc(uid)).append('&');
        }
        if (notBlank(agentId)) {
            path.append("agent_id=").append(enc(agentId)).append('&');
        }
        if (notBlank(runId)) {
            path.append("run_id=").append(enc(runId)).append('&');
        }
        if (limit > 0) {
            path.append("limit=").append(limit);
        }
        JsonNode resp = call("GET", path.toString(), null);
        return toList(resp);
    }

    public Map<String, Object> get(String memoryId) {
        JsonNode resp = call("GET", "/memories/" + enc(memoryId), null);
        if (resp.has("memory") && resp.get("memory").isObject()) {
            resp = resp.get("memory");
        }
        return toMap(resp);
    }

    public Map<String, Object> update(String memoryId, String text) {
        ObjectNode body = mapper.createObjectNode();
        body.put("text", text);
        JsonNode resp = call("PUT", "/memories/" + enc(memoryId), body);
        return toMap(resp);
    }

    public void delete(String memoryId) {
        call("DELETE", "/memories/" + enc(memoryId), null);
    }

    public void deleteAll(String mem0UserId, String agentId) {
        StringBuilder path = new StringBuilder("/memories?");
        if (notBlank(mem0UserId)) {
            path.append("user_id=").append(enc(mem0UserId)).append('&');
        }
        if (notBlank(agentId)) {
            path.append("agent_id=").append(enc(agentId));
        }
        call("DELETE", path.toString(), null);
    }

    public List<Map<String, Object>> search(String mem0UserId, String agentId, String runId,
                                            String query, int topK) {
        ObjectNode body = mapper.createObjectNode();
        body.put("query", query == null ? "" : query);
        if (notBlank(mem0UserId)) {
            body.put("user_id", mem0UserId);
        }
        if (notBlank(agentId)) {
            body.put("agent_id", agentId);
        }
        if (notBlank(runId)) {
            body.put("run_id", runId);
        }
        body.put("limit", Math.max(1, topK));
        JsonNode resp = call("POST", "/search", body);
        return toList(resp);
    }

    public boolean ping() {
        try {
            call("GET", "/", null);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private List<Map<String, Object>> toList(JsonNode resp) {
        List<Map<String, Object>> out = new ArrayList<>();
        JsonNode arr = resp;
        if (!resp.isArray()) {
            for (String k : new String[] {"results", "memories", "data"}) {
                if (resp.path(k).isArray()) {
                    arr = resp.path(k);
                    break;
                }
            }
        }
        if (arr.isArray()) {
            for (JsonNode n : arr) {
                out.add(toMap(n));
            }
        }
        return out;
    }

    private Map<String, Object> toMap(JsonNode n) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", n.path("id").asText(n.path("memory_id").asText("")));
        m.put("text", n.path("text").asText(n.path("memory").asText(n.path("content").asText(""))));
        m.put("score", n.path("score").asDouble(0.0));
        JsonNode meta = n.path("metadata");
        if (meta.isObject()) {
            Map<String, Object> mm = new LinkedHashMap<>();
            meta.fields().forEachRemaining(e -> mm.put(e.getKey(), e.getValue().asText(null)));
            m.put("metadata", mm);
        } else {
            m.put("metadata", Map.of());
        }
        return m;
    }

    private JsonNode call(String method, String path, JsonNode body) {
        if (properties == null || !properties.isConfigured()) {
            throw new CatalogException("MEM0_NOT_CONFIGURED", "Mem0 is not configured");
        }
        String url = properties.normalizedBaseUrl() + (path.startsWith("/") ? path : "/" + path);
        try {
            HttpRequest.Builder rb = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(Math.max(3, properties.timeoutSec() == null
                            ? 60 : properties.timeoutSec())))
                    .header("Content-Type", "application/json");
            if (notBlank(properties.apiKey())) {
                if (properties.isSelfHosted()) {
                    rb.header("X-API-Key", properties.apiKey().trim());
                } else {
                    rb.header("Authorization", "Token " + properties.apiKey().trim());
                }
            }
            if (body == null || "GET".equalsIgnoreCase(method) || "DELETE".equalsIgnoreCase(method)) {
                rb.method(method, HttpRequest.BodyPublishers.noBody());
            } else {
                rb.method(method, HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
            }
            HttpResponse<String> res = http.send(rb.build(), HttpResponse.BodyHandlers.ofString());
            int code = res.statusCode();
            String text = res.body() == null ? "" : res.body();
            if (code < 200 || code >= 300) {
                log.warn("mem0 admin {} {} -> {} {}", method, path, code, truncate(text));
                throw new CatalogException("MEM0_API_ERROR",
                        "mem0 " + method + " " + path + " failed: HTTP " + code + " " + truncate(text));
            }
            if (text.isBlank()) {
                return mapper.createObjectNode();
            }
            return mapper.readTree(text);
        } catch (CatalogException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CatalogException("MEM0_API_INTERRUPTED", "mem0 api interrupted");
        } catch (Exception e) {
            log.warn("mem0 admin {} {} error: {}", method, path, e.toString());
            throw new CatalogException("MEM0_API_ERROR",
                    "mem0 " + method + " " + path + " error: " + e.getMessage());
        }
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static String enc(String s) {
        return java.net.URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8);
    }

    private static String truncate(String s) {
        return s == null ? "" : (s.length() > 240 ? s.substring(0, 240) + "..." : s);
    }
}
