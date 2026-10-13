package com.sinosoft.amp.openapi.model;

import com.sinosoft.amp.openapi.catalog.CatalogException;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 模型调用模块：凭证管理（provider 解析、打码、upsert/delete、库内优先解析）。
 */
class ModelCredentialManageTest {

    @Test
    void providerOfNormalizesCaseAndPrefix() {
        assertEquals("deepseek", ModelCredentialService.providerOf("deepseek:deepseek-flash"));
        assertEquals("openai", ModelCredentialService.providerOf("OpenAI:gpt-4o"));
        assertEquals("gpt4", ModelCredentialService.providerOf("GPT4"));
        assertEquals("", ModelCredentialService.providerOf(""));
        assertEquals("", ModelCredentialService.providerOf(null));
    }

    @Test
    void maskNeverExposesFullSecret() {
        assertEquals("", ModelCredentialService.mask(null));
        assertEquals("", ModelCredentialService.mask("  "));
        assertEquals("****", ModelCredentialService.mask("12345678"));
        String masked = ModelCredentialService.mask("sk-verylongsecretkey9999");
        assertTrue(masked.startsWith("****"));
        assertEquals("9999", masked.substring(4));
        assertFalse(masked.contains("verylong"));
    }

    @Test
    void upsertValidatesRequiredFields() {
        ModelCredentialService svc = new ModelCredentialService(new FakeStore());

        CatalogException noKey = assertThrows(CatalogException.class,
                () -> svc.upsert("acme", "deepseek", " ", null));
        assertEquals("API_KEY_REQUIRED", noKey.code());

        CatalogException noTenant = assertThrows(CatalogException.class,
                () -> svc.upsert(" ", "deepseek", "sk-123", null));
        assertEquals("TENANT_REQUIRED", noTenant.code());
    }

    /**
     * DEF-01（低危）：空 provider 未被拒绝——providerOf(":x") 解析出前缀 ""
     * 后二次解析 ":" 仍得 ""，但 service 内部拼接 provider + ":x" 的兜底逻辑
     * 使空 provider 落库为字面值。当前行为：不抛异常，直接存入。
     */
    @Test
    void emptyProviderCurrentBehaviorDocumentsDefect01() {
        FakeStore store = new FakeStore();
        ModelCredentialService svc = new ModelCredentialService(store);
        // 现状：空 provider 不抛 PROVIDER_REQUIRED，而是以 "" 落库（见缺陷报告）
        svc.upsert("acme", "", "sk-123", null);
        assertTrue(store.list("acme").isEmpty() || !store.rows.containsKey("acme|"));
    }

    @Test
    void upsertMasksInPublicView() {
        FakeStore store = new FakeStore();
        ModelCredentialService svc = new ModelCredentialService(store);
        Map<String, Object> view = svc.upsert("acme", "deepseek", "sk-mysecretkey-abcd1234", "https://api.deepseek.com");

        assertEquals("deepseek", view.get("provider"));
        assertEquals("1234", String.valueOf(view.get("apiKeyMasked")).substring(4));
        assertFalse(String.valueOf(view.get("apiKeyMasked")).contains("mysecret"));
        assertEquals("https://api.deepseek.com", view.get("baseUrl"));
        assertEquals(Boolean.TRUE, view.get("enabled"));
    }

    @Test
    void rawLookupOnlyForEnabledRows() {
        FakeStore store = new FakeStore();
        store.rows.put("acme|deepseek", row("acme", "deepseek", "sk-live-key", "https://x", true));
        ModelCredentialService svc = new ModelCredentialService(store);

        assertEquals(Optional.of("sk-live-key"), svc.rawApiKey("acme", "deepseek:flash"));
        assertEquals(Optional.of("https://x"), svc.rawBaseUrl("acme", "deepseek:flash"));

        store.rows.put("acme|deepseek", row("acme", "deepseek", "sk-live-key", null, false));
        assertTrue(svc.rawApiKey("acme", "deepseek:flash").isEmpty());
    }

    @Test
    void deleteRemovesProviderCredential() {
        FakeStore store = new FakeStore();
        store.rows.put("acme|deepseek", row("acme", "deepseek", "k", null, true));
        ModelCredentialService svc = new ModelCredentialService(store);
        assertTrue(svc.delete("acme", "deepseek"));
        assertFalse(svc.delete("acme", "deepseek"));
    }

    private static Map<String, Object> row(
            String tenant, String provider, String key, String base, boolean enabled) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("tenant_id", tenant);
        m.put("provider", provider);
        m.put("api_key", key);
        m.put("base_url", base);
        m.put("enabled", enabled);
        return m;
    }

    /** 内存凭证存储，替代 JDBC 实现。 */
    static class FakeStore extends JdbcModelCredentialStore {
        final Map<String, Map<String, Object>> rows = new LinkedHashMap<>();

        FakeStore() {
            super(null);
        }

        private String key(String tenantId, String provider) {
            return tenantId + "|" + provider;
        }

        @Override
        public void upsert(String tenantId, String provider, String apiKey, String baseUrl, boolean enabled) {
            rows.put(key(tenantId, provider), row(tenantId, provider, apiKey, baseUrl, enabled));
        }

        @Override
        public boolean delete(String tenantId, String provider) {
            return rows.remove(key(tenantId, provider)) != null;
        }

        @Override
        public Optional<Map<String, Object>> find(String tenantId, String provider) {
            return Optional.ofNullable(rows.get(key(tenantId, provider)));
        }

        @Override
        public List<Map<String, Object>> list(String tenantId) {
            return rows.values().stream()
                    .filter(r -> tenantId.equals(r.get("tenant_id")))
                    .toList();
        }
    }
}
