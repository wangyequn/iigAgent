package com.sinosoft.amp.openapi.ops;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeConfigServiceTest {

    private RuntimeConfigService config() {
        return new RuntimeConfigService(new OpsProperties(true, 3, 3, 2));
    }

    @Test
    void rateLimitAllowsThenRejects() {
        RuntimeConfigService c = config();
        assertTrue(c.tryAcquire("k1"));
        assertTrue(c.tryAcquire("k1"));
        assertTrue(c.tryAcquire("k1"));
        assertFalse(c.tryAcquire("k1"));
        assertTrue(c.tryAcquire("k2"));
        assertEquals(1, c.rejectedTotal());
    }

    @Test
    void rateLimitDisabledAllowsAll() {
        RuntimeConfigService c = config();
        c.update(Map.of("rateLimitEnabled", false));
        for (int i = 0; i < 20; i++) {
            assertTrue(c.tryAcquire("k"));
        }
    }

    @Test
    void snapshotAndUpdate() {
        RuntimeConfigService c = config();
        c.update(Map.of("rateLimitPerMinute", 50));
        assertEquals(50, c.rateLimitPerMinute());
        assertEquals(50, c.snapshot().get("rateLimitPerMinute"));
    }
}
