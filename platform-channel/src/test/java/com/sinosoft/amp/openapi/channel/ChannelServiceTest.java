package com.sinosoft.amp.openapi.channel;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sinosoft.amp.openapi.orchestration.EchoWorkflowAgentInvoker;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChannelServiceTest {

    @Test
    void mockChannelRoundTrip() {
        MockChannelAdapter adapter = new MockChannelAdapter(new ObjectMapper());
        ChannelService svc = new ChannelService(List.of(adapter), new EchoWorkflowAgentInvoker(), null);
        Map<String, Object> resp = svc.handleInbound(
                "mock",
                "mock-channel-token",
                "{\"userId\":\"u1\",\"threadId\":\"t1\",\"text\":\"你好\"}");
        assertEquals("u1", resp.get("userId"));
        assertEquals("ch-t1", resp.get("sessionId"));
        assertTrue(String.valueOf(resp.get("reply")).contains("你好"));
        assertEquals(1, adapter.delivered().size());
    }

    @Test
    void invalidTokenRejected() {
        MockChannelAdapter adapter = new MockChannelAdapter(new ObjectMapper());
        ChannelService svc = new ChannelService(List.of(adapter), new EchoWorkflowAgentInvoker(), null);
        ChannelException ex = assertThrows(ChannelException.class,
                () -> svc.handleInbound("mock", "bad", "{\"text\":\"x\"}"));
        assertEquals("CHANNEL_SIGNATURE_INVALID", ex.code());
    }

    @Test
    void unknownChannelRejected() {
        ChannelService svc = new ChannelService(List.of(new MockChannelAdapter(new ObjectMapper())),
                new EchoWorkflowAgentInvoker(), null);
        ChannelException ex = assertThrows(ChannelException.class,
                () -> svc.handleInbound("feishu", "t", "{}"));
        assertEquals("CHANNEL_NOT_FOUND", ex.code());
    }
}
