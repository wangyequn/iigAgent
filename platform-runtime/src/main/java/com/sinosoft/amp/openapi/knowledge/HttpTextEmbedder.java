package com.sinosoft.amp.openapi.knowledge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;

/**
 * OpenAI 兼容 embeddings（/v1/embeddings）。失败时回退本地 hash，保证入库可用。
 */
public final class HttpTextEmbedder implements TextEmbedder {

    private static final Logger log = LoggerFactory.getLogger(HttpTextEmbedder.class);

    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final Integer dimensions;
    private final TextEmbedder fallback;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    public HttpTextEmbedder(String baseUrl, String apiKey, String model, Integer dimensions,
                            TextEmbedder fallback) {
        String b = baseUrl == null ? "" : baseUrl.trim();
        if (b.endsWith("/")) {
            b = b.substring(0, b.length() - 1);
        }
        this.baseUrl = b;
        this.apiKey = apiKey;
        this.model = model == null || model.isBlank() ? "text-embedding-3-small" : model;
        this.dimensions = dimensions;
        this.fallback = fallback == null ? new LocalHashTextEmbedder() : fallback;
    }

    @Override
    public double[] embed(String text) {
        if (text == null || text.isBlank()) {
            return fallback.embed(text);
        }
        try {
            ObjectNode body = mapper.createObjectNode();
            body.put("model", model);
            ArrayNode input = body.putArray("input");
            input.add(text);
            if (dimensions != null && dimensions > 0) {
                body.put("dimensions", dimensions);
            }
            HttpRequest.Builder rb = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/v1/embeddings"))
                    .timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
            if (apiKey != null && !apiKey.isBlank()) {
                rb.header("Authorization", "Bearer " + apiKey);
            }
            HttpResponse<String> resp = http.send(rb.build(), HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() >= 400) {
                log.warn("embedding http {} body={}", resp.statusCode(), abbreviate(resp.body()));
                return fallback.embed(text);
            }
            JsonNode root = mapper.readTree(resp.body());
            JsonNode arr = root.path("data");
            if (!arr.isArray() || arr.isEmpty()) {
                return fallback.embed(text);
            }
            JsonNode emb = arr.get(0).path("embedding");
            if (!emb.isArray() || emb.isEmpty()) {
                return fallback.embed(text);
            }
            double[] v = new double[emb.size()];
            for (int i = 0; i < v.length; i++) {
                v[i] = emb.get(i).asDouble();
            }
            return v;
        } catch (Exception e) {
            log.warn("embedding http failed, fallback hash: {}", e.toString());
            return fallback.embed(text);
        }
    }

    @Override
    public String id() {
        return "http:" + model.toLowerCase(Locale.ROOT);
    }

    private static String abbreviate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() > 120 ? s.substring(0, 120) + "..." : s;
    }
}
