package com.sinosoft.amp.openapi.ops;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OtelBridgeTest {

    @Test
    void disabledModeIsLocal() {
        OtelBridge bridge = new OtelBridge(new OtelProperties(false, null, "svc"));
        bridge.start();
        assertEquals("disabled", bridge.mode());
        assertEquals(false, bridge.status().get("enabled"));
    }

    @Test
    void loggingExporterWhenNoEndpoint() {
        OtelBridge bridge = new OtelBridge(new OtelProperties(true, null, "amp-test"));
        bridge.start();
        assertEquals("logging", bridge.mode());
        assertTrue(Boolean.TRUE.equals(bridge.status().get("hasTracer")));
        Integer v = bridge.inSpan("unit.span", Map.of("k", "v"), () -> 42);
        assertEquals(42, v);
        bridge.close();
    }

    @Test
    void inSpanPropagatesException() {
        OtelBridge bridge = new OtelBridge(new OtelProperties(true, null, "amp-test"));
        bridge.start();
        try {
            bridge.inSpan("unit.err", Map.of(), () -> {
                throw new IllegalStateException("boom");
            });
        } catch (IllegalStateException e) {
            assertEquals("boom", e.getMessage());
        }
        bridge.close();
    }
}
