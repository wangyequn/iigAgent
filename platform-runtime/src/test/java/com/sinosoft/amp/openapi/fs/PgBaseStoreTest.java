package com.sinosoft.amp.openapi.fs;

import io.agentscope.harness.agent.filesystem.remote.store.StoreItem;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * pathKey 与 JSON 映射契约（Jdbc 实现需 PG；此处测纯逻辑）。
 */
class PgBaseStoreTest {

    @Test
    void pathKeyNormalizesSegments() {
        assertEquals("/", PgBaseStore.pathKey(List.of()));
        assertEquals("/agents/demo", PgBaseStore.pathKey(List.of("agents", "demo")));
        assertEquals("/agents/demo/users/u1",
                PgBaseStore.pathKey(List.of("agents", "demo", "users", "u1")));
        // 斜杠注入折叠
        assertEquals("/agents/a_b", PgBaseStore.pathKey(List.of("agents", "a/b")));
    }

    @Test
    void jsonToMapRoundTripShape() {
        Map<String, Object> m = PgBaseStore.jsonToMap("{\"content\":\"hello\",\"encoding\":\"utf-8\"}");
        assertEquals("hello", m.get("content"));
        assertTrue(PgBaseStore.jsonToMap(null).isEmpty());
        assertTrue(PgBaseStore.jsonToMap("not-json").containsKey("raw"));
    }

    @Test
    void storeItemCarriesVersion() {
        Map<String, Object> v = new HashMap<>();
        v.put("content", "x");
        StoreItem item = new StoreItem("f.md", v, 3);
        assertEquals(3, item.version());
        assertEquals("f.md", item.key());
    }
}
