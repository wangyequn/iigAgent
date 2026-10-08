package com.sinosoft.amp.openapi.ops;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReleaseEventBusTest {

    @Test
    void handleMessageAppliesRemoteReleaseSwitch() {
        ReleaseEventBus bus = new ReleaseEventBus("redis://127.0.0.1:6379", false);
        AtomicReference<Map<String, Object>> seen = new AtomicReference<>();
        bus.setHandler(seen::set);
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("type", ReleaseEventBus.TYPE_RELEASE_SWITCH);
        e.put("tenantId", "acme");
        e.put("agentId", "demo");
        e.put("fromVersion", 1);
        e.put("toVersion", 2);
        e.put("origin", "other-replica");
        bus.handleMessage("{\"type\":\"release_switch\",\"tenantId\":\"acme\",\"agentId\":\"demo\",\"fromVersion\":1,\"toVersion\":2,\"origin\":\"other-replica\"}");
        assertEquals("release_switch", seen.get().get("type"));
    }

    @Test
    void ignoresOwnOrigin() {
        ReleaseEventBus bus = new ReleaseEventBus("redis://127.0.0.1:6379", false);
        AtomicReference<Map<String, Object>> seen = new AtomicReference<>();
        bus.setHandler(seen::set);
        bus.handleMessage("{\"type\":\"release_switch\",\"origin\":\"" + bus.replicaId() + "\"}");
        assertTrue(seen.get() == null);
    }

    @Test
    void disabledBusDoesNotStart() {
        ReleaseEventBus bus = new ReleaseEventBus("redis://127.0.0.1:6379", false);
        assertFalse(bus.enabled());
    }
}
