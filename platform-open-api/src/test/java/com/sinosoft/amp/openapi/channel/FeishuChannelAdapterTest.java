package com.sinosoft.amp.openapi.channel;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FeishuChannelAdapterTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final FeishuChannelAdapter adapter = new FeishuChannelAdapter(
            mapper, "tok123", new FeishuMessageSender(mapper, "", "", "https://open.feishu.cn/open-apis"));

    @Test
    void verifyTokenAccepted() {
        adapter.verifySignature("tok123", null, "{}");
    }

    @Test
    void challengeAcceptedWithoutToken() {
        adapter.verifySignature(null, null, "{\"type\":\"url_verification\",\"challenge\":\"c1\"}");
        ChannelInboundMessage msg = adapter.parseInbound("{\"type\":\"url_verification\",\"challenge\":\"c1\"}");
        assertEquals("c1", msg.text());
    }

    @Test
    void parseMessageEvent() {
        String body = "{\"event\":{\"message\":{\"content\":\"{\\\"text\\\":\\\"你好\\\"}\",\"chat_id\":\"oc1\"},"
                + "\"sender\":{\"sender_id\":{\"open_id\":\"ou1\"}}}}";
        ChannelInboundMessage msg = adapter.parseInbound(body);
        assertEquals("ou1", msg.externalUserId());
        assertEquals("oc1", msg.externalThreadId());
        assertEquals("你好", msg.text());
    }

    @Test
    void badTokenRejected() {
        assertThrows(ChannelException.class, () -> adapter.verifySignature("bad", null, "{}"));
    }
}
