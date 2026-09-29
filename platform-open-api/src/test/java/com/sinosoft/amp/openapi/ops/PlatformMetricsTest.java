package com.sinosoft.amp.openapi.ops;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlatformMetricsTest {

    @Test
    void recordsChatTokensAndTools() {
        RuntimeConfigService cfg = new RuntimeConfigService(new OpsProperties(true, 100, 4, 2));
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        PlatformMetrics metrics = new PlatformMetrics(registry, cfg);

        metrics.recordChat("demo", 12, false);
        metrics.recordChat("demo", 30, true);
        metrics.recordTokens("demo", 10, 20);
        metrics.recordTool("place_order");
        metrics.recordHitl(true);

        assertEquals(2.0, registry.counter("amp.chat.requests").count());
        assertEquals(1.0, registry.counter("amp.chat.errors").count());
        assertEquals(10.0, registry.counter("amp.tokens.prompt").count());
        assertEquals(20.0, registry.counter("amp.tokens.completion").count());
        assertEquals(1.0, registry.counter("amp.tool.calls").count());
        assertEquals(1.0, registry.counter("amp.hitl.confirms").count());
        assertTrue(registry.find("amp.chat.latency").timer() != null);

        assertEquals(3, metrics.estimateTokens("abcdefghijkl")); // 12 chars / 4
    }
}
