package com.sinosoft.amp.openapi.channel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * 飞书 OpenAPI 发送：tenant_access_token + im.v1.message.create。
 * 未配置 app_id 时 deliver 降级为日志（骨架模式）。
 */
@Component
public class FeishuMessageSender {

    private static final Logger log = LoggerFactory.getLogger(FeishuMessageSender.class);

    private final ObjectMapper objectMapper;
    private final String appId;
    private final String appSecret;
    private final String openBaseUrl;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private volatile String cachedToken;
    private volatile long tokenExpireAtMs;

    public FeishuMessageSender(
            ObjectMapper objectMapper,
            @Value("${amp.channel.feishu.app-id:}") String appId,
            @Value("${amp.channel.feishu.app-secret:}") String appSecret,
            @Value("${amp.channel.feishu.open-base:https://open.feishu.cn/open-apis}") String openBaseUrl) {
        this.objectMapper = objectMapper;
        this.appId = appId;
        this.appSecret = appSecret;
        this.openBaseUrl = openBaseUrl.endsWith("/")
                ? openBaseUrl.substring(0, openBaseUrl.length() - 1)
                : openBaseUrl;
    }

    public boolean configured() {
        return appId != null && !appId.isBlank() && appSecret != null && !appSecret.isBlank();
    }

    public void sendText(String receiveId, String text) {
        if (!configured()) {
            log.info("feishu sender not configured, skip deliver to={} text={}", receiveId, text);
            return;
        }
        try {
            String token = tenantAccessToken();
            ObjectNode body = objectMapper.createObjectNode();
            body.put("receive_id", receiveId == null ? "" : receiveId);
            body.put("msg_type", "text");
            body.put("content", objectMapper.createObjectNode().put("text", text == null ? "" : text).toString());
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(openBaseUrl + "/im/v1/messages?receive_id_type=open_id"))
                    .timeout(Duration.ofSeconds(10))
                    .header("Authorization", "Bearer " + token)
                    .header("Content-Type", "application/json; charset=utf-8")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() >= 300) {
                log.warn("feishu send failed status={} body={}", resp.statusCode(), resp.body());
                throw new ChannelException("CHANNEL_DELIVER_FAILED", "feishu send status " + resp.statusCode());
            }
            JsonNode n = objectMapper.readTree(resp.body());
            if (n.path("code").asInt(0) != 0) {
                throw new ChannelException("CHANNEL_DELIVER_FAILED", "feishu code=" + n.path("code").asInt());
            }
            log.info("feishu send ok to={}", receiveId);
        } catch (ChannelException e) {
            throw e;
        } catch (Exception e) {
            throw new ChannelException("CHANNEL_DELIVER_FAILED", "feishu send error: " + e.getMessage());
        }
    }

    private String tenantAccessToken() throws Exception {
        long now = System.currentTimeMillis();
        if (cachedToken != null && now < tokenExpireAtMs - 60_000L) {
            return cachedToken;
        }
        ObjectNode body = objectMapper.createObjectNode();
        body.put("app_id", appId);
        body.put("app_secret", appSecret);
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(openBaseUrl + "/auth/v3/tenant_access_token/internal"))
                .timeout(Duration.ofSeconds(8))
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        JsonNode n = objectMapper.readTree(resp.body());
        if (n.path("code").asInt(0) != 0) {
            throw new ChannelException("CHANNEL_AUTH_FAILED", "feishu token code=" + n.path("code").asInt());
        }
        cachedToken = n.path("tenant_access_token").asText();
        tokenExpireAtMs = now + n.path("expire").asInt(3600) * 1000L;
        return cachedToken;
    }
}
