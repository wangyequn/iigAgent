package com.sinosoft.amp.openapi.channel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Mock Channel：本地联调与契约测试用。签名约定：HMAC 不做，仅校验 X-Channel-Token。
 */
@Component
public class MockChannelAdapter implements ChannelAdapter {

    public static final String TYPE = "mock";

    private static final Logger log = LoggerFactory.getLogger(MockChannelAdapter.class);

    private final ObjectMapper objectMapper;
    private final List<ChannelOutboundMessage> delivered = new ArrayList<>();
    private String expectedToken = "mock-channel-token";

    public MockChannelAdapter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public void setExpectedToken(String expectedToken) {
        this.expectedToken = expectedToken;
    }

    @Override
    public String channelType() {
        return TYPE;
    }

    @Override
    public void verifySignature(String signature, String timestamp, String rawBody) {
        if (signature == null || !signature.equals(expectedToken)) {
            throw new ChannelException("CHANNEL_SIGNATURE_INVALID", "mock channel token mismatch");
        }
    }

    @Override
    public ChannelInboundMessage parseInbound(String rawBody) {
        try {
            JsonNode n = objectMapper.readTree(rawBody == null ? "{}" : rawBody);
            return new ChannelInboundMessage(
                    TYPE,
                    text(n, "userId"),
                    text(n, "threadId"),
                    text(n, "text"),
                    rawBody);
        } catch (ChannelException e) {
            throw e;
        } catch (Exception e) {
            throw new ChannelException("CHANNEL_PAYLOAD_INVALID", "cannot parse mock inbound: " + e.getMessage());
        }
    }

    @Override
    public void deliver(String targetUser, ChannelOutboundMessage message) {
        delivered.add(message);
        log.info("mock channel deliver to={} text={}", targetUser, message.text());
    }

    public List<ChannelOutboundMessage> delivered() {
        return List.copyOf(delivered);
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    public static String normalizeType(String type) {
        return type == null ? TYPE : type.toLowerCase(Locale.ROOT);
    }
}
