package com.sinosoft.amp.openapi.external;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sinosoft.amp.openapi.catalog.CatalogException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * S8 External 最小集：Chat 转发（仅非流式 call）。
 * 中台不做外部侧编排，只按注册的 baseUrl 转发 messages。
 */
@Component
public class ExternalChatClient {

    private static final Logger log = LoggerFactory.getLogger(ExternalChatClient.class);

    private final ExternalAgentService service;
    private final ObjectMapper objectMapper;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    public ExternalChatClient(ExternalAgentService service, ObjectMapper objectMapper) {
        this.service = service;
        this.objectMapper = objectMapper;
    }

    public boolean hasExternal(String tenantId, String agentId) {
        return service.requireHealthyByName(tenantId, agentId) != null;
    }

    /**
     * 转发非流式对话到 External Agent。
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> forwardMessage(String tenantId, String agentId,
                                              String userId, String sessionId, String message) {
        ExternalAgentRecord agent = service.requireHealthyByName(tenantId, agentId);
        if (agent == null) {
            throw new CatalogException("AGENT_NOT_FOUND", "agent not found (managed or external): " + agentId);
        }
        String base = agent.baseUrl();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        // 约定外部进程暴露 POST {baseUrl}/api/v1/messages 或 /messages
        String url = base + "/api/v1/messages";
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("agentId", agentId);
            body.put("tenantId", tenantId);
            body.put("userId", userId);
            body.put("sessionId", sessionId);
            body.put("message", message);
            HttpRequest.Builder rb = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)));
            if (agent.callbackKey() != null && !agent.callbackKey().isBlank()) {
                rb.header("X-Callback-Key", agent.callbackKey());
            }
            HttpResponse<String> resp = http.send(rb.build(), HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() >= 400) {
                throw new CatalogException("EXTERNAL_CHAT_FAILED",
                        "external agent HTTP " + resp.statusCode() + ": " + abbreviate(resp.body()));
            }
            Map<String, Object> parsed = objectMapper.readValue(resp.body(), Map.class);
            // 规范化为平台 ChatReply 形状
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("agentId", agentId);
            out.put("sessionId", sessionId);
            out.put("reply", String.valueOf(parsed.getOrDefault("reply",
                    parsed.getOrDefault("text", parsed.getOrDefault("message", "")))));
            out.put("source", "external:" + agent.name());
            out.put("externalStatus", agent.status());
            return out;
        } catch (CatalogException e) {
            throw e;
        } catch (Exception e) {
            log.warn("external chat forward failed agent={} url={} err={}", agentId, url, e.toString());
            throw new CatalogException("EXTERNAL_CHAT_FAILED", "forward failed: " + e.getMessage());
        }
    }

    /**
     * S9：流式转发。外部需暴露 POST {baseUrl}/api/v1/messages:stream 且返回 text/event-stream。
     * 平台透传 SSE 帧；失败抛 EXTERNAL_CHAT_FAILED。
     */
    public void forwardStream(String tenantId, String agentId,
                              String userId, String sessionId, String message,
                              java.util.function.Consumer<String> sseChunkConsumer) {
        ExternalAgentRecord agent = service.requireHealthyByName(tenantId, agentId);
        if (agent == null) {
            throw new CatalogException("AGENT_NOT_FOUND", "agent not found (managed or external): " + agentId);
        }
        String base = agent.baseUrl();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        String url = base + "/api/v1/messages:stream";
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("agentId", agentId);
            body.put("tenantId", tenantId);
            body.put("userId", userId);
            body.put("sessionId", sessionId);
            body.put("message", message);
            HttpRequest.Builder rb = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(120))
                    .header("Content-Type", "application/json")
                    .header("Accept", "text/event-stream")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)));
            if (agent.callbackKey() != null && !agent.callbackKey().isBlank()) {
                rb.header("X-Callback-Key", agent.callbackKey());
            }
            HttpResponse<java.util.stream.Stream<String>> resp =
                    http.send(rb.build(), HttpResponse.BodyHandlers.ofLines());
            if (resp.statusCode() >= 400) {
                throw new CatalogException("EXTERNAL_CHAT_FAILED",
                        "external stream HTTP " + resp.statusCode());
            }
            resp.body().forEach(sseChunkConsumer);
        } catch (CatalogException e) {
            throw e;
        } catch (Exception e) {
            log.warn("external stream forward failed agent={} url={} err={}", agentId, url, e.toString());
            throw new CatalogException("EXTERNAL_CHAT_FAILED", "stream forward failed: " + e.getMessage());
        }
    }

    private static String abbreviate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() > 160 ? s.substring(0, 160) + "..." : s;
    }
}
