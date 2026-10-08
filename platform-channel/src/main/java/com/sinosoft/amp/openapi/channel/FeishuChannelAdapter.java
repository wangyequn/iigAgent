package com.sinosoft.amp.openapi.channel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 飞书 Channel 适配骨架（S11）：解析事件回调文本、校验 token。
 * 发消息需配置 app_id/secret 后走飞书 OpenAPI；当前 deliver 记日志占位。
 */
@Component
public class FeishuChannelAdapter implements ChannelAdapter {

    public static final String TYPE = "feishu";

    private static final Logger log = LoggerFactory.getLogger(FeishuChannelAdapter.class);

    private final ObjectMapper objectMapper;
    private final String verifyToken;
    private final FeishuMessageSender sender;

    public FeishuChannelAdapter(
            ObjectMapper objectMapper,
            @Value("${amp.channel.feishu.verify-token:feishu-verify}") String verifyToken,
            FeishuMessageSender sender) {
        this.objectMapper = objectMapper;
        this.verifyToken = verifyToken;
        this.sender = sender;
    }

    @Override
    public String channelType() {
        return TYPE;
    }

    @Override
    public void verifySignature(String signature, String timestamp, String rawBody) {
        if (signature != null && signature.equals(verifyToken)) {
            return;
        }
        try {
            JsonNode n = objectMapper.readTree(rawBody == null ? "{}" : rawBody);
            String token = n.path("token").asText(null);
            if (verifyToken.equals(token)) {
                return;
            }
            if (n.hasNonNull("challenge") && "url_verification".equals(n.path("type").asText(""))) {
                return;
            }
        } catch (Exception ignored) {
            // fallthrough
        }
        throw new ChannelException("CHANNEL_SIGNATURE_INVALID", "feishu verify token mismatch");
    }

    @Override
    public ChannelInboundMessage parseInbound(String rawBody) {
        try {
            JsonNode root = objectMapper.readTree(rawBody == null ? "{}" : rawBody);
            if (root.hasNonNull("challenge")) {
                return new ChannelInboundMessage(TYPE, "feishu-verify", "sys", root.path("challenge").asText(), rawBody);
            }
            JsonNode event = root.path("event");
            JsonNode msg = event.path("message");
            JsonNode sender = event.path("sender");
            String text = msg.path("content").asText("");
            if (text.startsWith("{")) {
                try {
                    text = objectMapper.readTree(text).path("text").asText(text);
                } catch (Exception ignored) {
                    // keep raw
                }
            }
            String userId = sender.path("sender_id").path("open_id").asText("anonymous");
            return new ChannelInboundMessage(TYPE, userId, msg.path("chat_id").asText("default"), text, rawBody);
        } catch (Exception e) {
            throw new ChannelException("CHANNEL_PAYLOAD_INVALID", "cannot parse feishu inbound: " + e.getMessage());
        }
    }

    @Override
    public void deliver(String targetUser, ChannelOutboundMessage message) {
        sender.sendText(targetUser, message.text());
    }
}
