package com.sinosoft.amp.openapi.ops;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * T8 Loki HTTP：push（/loki/api/v1/push）+ query_range（会话检索）。
 * 失败不抛到业务线程，仅告警。
 */
@Component
public class LokiLogClient {

    private static final Logger log = LoggerFactory.getLogger(LokiLogClient.class);

    private final LokiProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();

    public LokiLogClient(LokiProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public boolean enabled() {
        return properties.enabled();
    }

    /**
     * 推送一条会话相关日志到 Loki。
     */
    public boolean push(String line, Map<String, String> labels) {
        if (!properties.enabled()) {
            return false;
        }
        try {
            Map<String, String> streamLabels = new LinkedHashMap<>();
            streamLabels.put("app", blankTo(properties.app(), "platform-open-api"));
            streamLabels.put("job", "amp-open-api");
            if (labels != null) {
                labels.forEach((k, v) -> {
                    if (v != null && !v.isBlank()) {
                        streamLabels.put(k, v);
                    }
                });
            }
            long ns = System.currentTimeMillis() * 1_000_000L;
            List<Object> values = List.of(List.of(String.valueOf(ns), line == null ? "" : line));
            Map<String, Object> stream = new LinkedHashMap<>();
            stream.put("stream", streamLabels);
            stream.put("values", List.of(values));
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("streams", List.of(stream));

            String url = baseUrl() + "/loki/api/v1/push";
            HttpRequest.Builder rb = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(3))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)));
            applyAuth(rb);
            HttpResponse<String> resp = http.send(rb.build(), HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() >= 300) {
                log.warn("Loki push failed status={} body={}", resp.statusCode(), abbreviate(resp.body()));
                return false;
            }
            return true;
        } catch (Exception e) {
            log.warn("Loki push error: {}", e.toString());
            return false;
        }
    }

    /**
     * 按 sessionId 查会话日志（LogQL）。
     */
    @SuppressWarnings("unchecked")
    public List<String> querySession(String sessionId, int limit) {
        List<String> out = new ArrayList<>();
        if (!properties.enabled() || sessionId == null || sessionId.isBlank()) {
            return out;
        }
        try {
            String app = blankTo(properties.app(), "platform-open-api");
            String logql = "{app=\"" + app + "\"} |= \"sessionId=" + sessionId + "\"";
            String url = baseUrl() + "/loki/api/v1/query_range?query="
                    + URLEncoder.encode(logql, StandardCharsets.UTF_8)
                    + "&limit=" + Math.max(1, Math.min(limit, 200));
            HttpRequest.Builder rb = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(5))
                    .GET();
            applyAuth(rb);
            HttpResponse<String> resp = http.send(rb.build(), HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() >= 300) {
                log.warn("Loki query failed status={}", resp.statusCode());
                return out;
            }
            Map<String, Object> body = objectMapper.readValue(resp.body(), Map.class);
            Map<String, Object> data = (Map<String, Object>) body.get("data");
            if (data == null) {
                return out;
            }
            List<Object> result = (List<Object>) data.get("result");
            if (result == null) {
                return out;
            }
            for (Object r : result) {
                Map<String, Object> res = (Map<String, Object>) r;
                List<Object> values = (List<Object>) res.get("values");
                if (values == null) {
                    continue;
                }
                for (Object v : values) {
                    List<Object> pair = (List<Object>) v;
                    if (pair.size() >= 2) {
                        out.add(String.valueOf(pair.get(1)));
                    }
                }
            }
            return out;
        } catch (Exception e) {
            log.warn("Loki query error: {}", e.toString());
            return out;
        }
    }

    public Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", properties.enabled());
        m.put("url", properties.url());
        m.put("app", properties.app());
        m.put("localLogFile", properties.localLogFile());
        return m;
    }

    private String baseUrl() {
        String u = properties.url();
        if (u == null || u.isBlank()) {
            u = "http://127.0.0.1:3100";
        }
        return u.endsWith("/") ? u.substring(0, u.length() - 1) : u;
    }

    private void applyAuth(HttpRequest.Builder rb) {
        if (properties.username() != null && !properties.username().isBlank()) {
            String cred = properties.username() + ":" + (properties.password() == null ? "" : properties.password());
            rb.header("Authorization", "Basic " + Base64.getEncoder()
                    .encodeToString(cred.getBytes(StandardCharsets.UTF_8)));
        }
    }

    private static String blankTo(String v, String d) {
        return v == null || v.isBlank() ? d : v;
    }

    private static String abbreviate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() > 120 ? s.substring(0, 120) + "..." : s;
    }
}
