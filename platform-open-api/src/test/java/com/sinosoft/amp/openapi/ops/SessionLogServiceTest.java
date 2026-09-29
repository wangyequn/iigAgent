package com.sinosoft.amp.openapi.ops;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionLogServiceTest {

    @Test
    void localSearchFindsSessionLines() throws Exception {
        Path tmp = Files.createTempFile("amp-loki", ".log");
        Files.writeString(tmp, """
                1 sessionEvent level=INFO sessionId=agt_x1 agentId=demo userId=u1 chat.done
                2 other
                3 sessionEvent level=INFO sessionId=agt_x1 agentId=demo stream.done
                """, StandardCharsets.UTF_8);
        LokiProperties props = new LokiProperties(false, "http://127.0.0.1:3100", null, null, "amp",
                tmp.toString());
        SessionLogService svc = new SessionLogService(new LokiLogClient(props, new com.fasterxml.jackson.databind.ObjectMapper()), props);
        Map<String, Object> r = svc.searchSession("agt_x1", 10);
        assertEquals("local", r.get("source"));
        assertEquals(2, r.get("count"));
        List<?> lines = (List<?>) r.get("lines");
        assertTrue(lines.get(0).toString().contains("chat.done"));
    }

    @Test
    void disabledLokiPushReturnsFalse() {
        LokiProperties props = new LokiProperties(false, "http://127.0.0.1:3100", null, null, "amp", null);
        LokiLogClient client = new LokiLogClient(props, new com.fasterxml.jackson.databind.ObjectMapper());
        assertFalse(client.push("x", Map.of("sessionId", "s")));
        assertTrue(client.querySession("s", 5).isEmpty());
    }

    @Test
    void sessionEventAlwaysWritesLocal() {
        LokiProperties props = new LokiProperties(false, null, null, null, "amp", null);
        SessionLogService svc = new SessionLogService(
                new LokiLogClient(props, new com.fasterxml.jackson.databind.ObjectMapper()), props);
        // 不抛异常即可
        svc.sessionEvent("INFO", "agt_z", "demo", "u1", "hello");
    }
}
