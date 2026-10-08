package com.sinosoft.amp.openapi.ops;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NacosAiSyncServiceTest {

    @Test
    void sanitizeNameFitsExternalContract() {
        assertEquals("abc", NacosAiSyncService.sanitizeName("abc"));
        assertEquals("a_b", NacosAiSyncService.sanitizeName("a.b"));
        assertNull(NacosAiSyncService.sanitizeName("   "));
        assertTrue(NacosAiSyncService.sanitizeName("x".repeat(80)).length() <= 64);
    }

    @Test
    void enabledToolsFromToolArray() {
        Map<String, Object> tools = new LinkedHashMap<>();
        tools.put("tools", List.of(
                Map.of("name", "t1", "enabled", true),
                Map.of("name", "t2", "enabled", false),
                Map.of("name", "t3")));
        assertEquals("t1,t3", NacosAiAdminClient.enabledToolsFrom(tools));
    }

    @Test
    void enabledToolsFromRealNacosToolSpecNoEnabledField() {
        // 真实 Nacos：tools 无 enabled，toolsMeta 可为空 → 全部视为启用
        Map<String, Object> toolSpec = new LinkedHashMap<>();
        toolSpec.put("tools", List.of(
                Map.of("name", "query_orders", "description", "q"),
                Map.of("name", "place_order", "description", "p")));
        toolSpec.put("toolsMeta", new LinkedHashMap<>());
        assertEquals("query_orders,place_order", NacosAiAdminClient.enabledToolsFrom(toolSpec));
    }

    @Test
    void enabledToolsRespectsToolsMetaEnabledFalse() {
        Map<String, Object> toolSpec = new LinkedHashMap<>();
        toolSpec.put("tools", List.of(
                Map.of("name", "query_orders"),
                Map.of("name", "place_order"),
                Map.of("name", "hidden_tool")));
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("query_orders", Map.of("enabled", true));
        meta.put("place_order", Map.of("enabled", true));
        meta.put("hidden_tool", Map.of("enabled", false));
        toolSpec.put("toolsMeta", meta);
        assertEquals("query_orders,place_order", NacosAiAdminClient.enabledToolsFrom(toolSpec));
    }

    @Test
    void toolsMetaOverridesToolLevelEnabled() {
        Map<String, Object> toolSpec = new LinkedHashMap<>();
        toolSpec.put("tools", List.of(
                Map.of("name", "t1", "enabled", true),
                Map.of("name", "t2", "enabled", true)));
        toolSpec.put("toolsMeta", Map.of(
                "t1", Map.of("enabled", true),
                "t2", Map.of("enabled", false)));
        assertEquals("t1", NacosAiAdminClient.enabledToolsFrom(toolSpec));
    }

    @Test
    void urlFromBackendEndpoints() {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("backendEndpoints", List.of(Map.of("address", "127.0.0.1", "port", 19099, "path", "/mcp")));
        assertEquals("http://127.0.0.1:19099/mcp", NacosAiAdminClient.urlFrom(detail));
    }

    @Test
    void enabledToolsFromNameMap() {
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("query_orders", true);
        spec.put("place_order", false);
        assertEquals("query_orders", NacosAiAdminClient.enabledToolsFrom(spec));
    }

    @Test
    void enabledToolsEmptyOrNull() {
        assertNull(NacosAiAdminClient.enabledToolsFrom(null));
        assertNull(NacosAiAdminClient.enabledToolsFrom(Map.of("tools", List.of())));
    }

    @Test
    void propertiesNamespaceSharedAndIdentity() {
        NacosAiProperties shared = new NacosAiProperties(
                true, "127.0.0.1:8848", "shared", "public", null, null, "/nacos",
                NacosAiProperties.SYNC_NACOS_TO_LOCAL, NacosAiProperties.AGENT_CARD_DISCOVER_ONLY,
                "serverIdentity", "security", false, true, true, "stable", "latest", "amp.agent.", 60_000L,
                "amp-");
        assertEquals("public", shared.effectiveNamespace("acme"));
        NacosAiProperties identity = new NacosAiProperties(
                true, "127.0.0.1:8848", "identity", "public", null, null, null,
                NacosAiProperties.SYNC_NACOS_TO_LOCAL, NacosAiProperties.AGENT_CARD_DISCOVER_ONLY,
                null, null, false, false, false, null, null, null, 0L, null);
        assertEquals("acme", identity.effectiveNamespace("acme"));
        NacosAiProperties prefix = new NacosAiProperties(
                true, "127.0.0.1:8848", "prefix", "public", null, null, null,
                NacosAiProperties.SYNC_NACOS_TO_LOCAL, NacosAiProperties.AGENT_CARD_DISCOVER_ONLY,
                null, null, false, false, false, null, null, null, 0L, "amp-");
        assertEquals("amp-acme", prefix.effectiveNamespace("acme"));
        assertTrue(shared.baseUrl().endsWith("/nacos"));
    }

    @Test
    void skillImportNameSanitized() {
        assertEquals("a_b", NacosAiSyncService.sanitizeName("a.b"));
    }
}
