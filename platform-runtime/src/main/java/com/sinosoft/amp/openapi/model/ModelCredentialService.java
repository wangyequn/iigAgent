package com.sinosoft.amp.openapi.model;

import com.sinosoft.amp.openapi.catalog.CatalogException;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ModelCreationContext;
import io.agentscope.core.model.ModelRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * 模型凭证最小闭环：落库 → 打码查询 → 构建 Model 时注入 ModelCreationContext.apiKey。
 * 环境变量（DEEPSEEK_API_KEY 等）仍作回退；库内优先。
 */
@Service
public class ModelCredentialService {

    private static final Logger log = LoggerFactory.getLogger(ModelCredentialService.class);

    private final JdbcModelCredentialStore store;

    public ModelCredentialService(JdbcModelCredentialStore store) {
        this.store = store;
    }

    /** modelId 形如 deepseek:deepseek-flash → provider=deepseek。 */
    public static String providerOf(String modelId) {
        if (modelId == null || modelId.isBlank()) {
            return "";
        }
        int i = modelId.indexOf(':');
        String p = i > 0 ? modelId.substring(0, i) : modelId;
        return p.trim().toLowerCase(Locale.ROOT);
    }

    public static String mask(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            return "";
        }
        if (apiKey.length() <= 8) {
            return "****";
        }
        return "****" + apiKey.substring(apiKey.length() - 4);
    }

    public Map<String, Object> upsert(String tenantId, String provider, String apiKey, String baseUrl) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new CatalogException("TENANT_REQUIRED", "tenantId required");
        }
        String p = providerOf(provider == null ? "" : provider + ":x");
        if (p.isEmpty()) {
            p = providerOf(provider);
        }
        if (p.isEmpty()) {
            throw new CatalogException("PROVIDER_REQUIRED", "provider required");
        }
        if (apiKey == null || apiKey.isBlank()) {
            throw new CatalogException("API_KEY_REQUIRED", "apiKey required");
        }
        store.upsert(tenantId, p, apiKey.trim(), blankToNull(baseUrl), true);
        log.info("model credential upserted tenant={} provider={} keyMasked={}", tenantId, p, mask(apiKey));
        return publicView(tenantId, p, mask(apiKey), blankToNull(baseUrl), true);
    }

    public boolean delete(String tenantId, String provider) {
        String p = providerOf(provider);
        return store.delete(tenantId, p);
    }

    public List<Map<String, Object>> listMasked(String tenantId) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> row : store.list(tenantId)) {
            out.add(toPublic(row));
        }
        return out;
    }

    public Optional<String> rawApiKey(String tenantId, String modelId) {
        String provider = providerOf(modelId);
        return store.find(tenantId, provider)
                .filter(row -> Boolean.TRUE.equals(row.get("enabled")))
                .map(row -> String.valueOf(row.get("api_key")))
                .filter(k -> k != null && !k.isBlank() && !"null".equals(k));
    }

    public Optional<String> rawBaseUrl(String tenantId, String modelId) {
        String provider = providerOf(modelId);
        return store.find(tenantId, provider)
                .map(row -> row.get("base_url") == null ? null : String.valueOf(row.get("base_url")))
                .filter(k -> k != null && !k.isBlank() && !"null".equals(k));
    }

    /** 库内凭证优先注入；无则回退环境变量（AgentScope Provider 默认行为）。 */
    public Model resolveModel(String tenantId, String modelId) {
        ModelCreationContext.Builder ctx = ModelCreationContext.builder();
        boolean customized = false;
        String key = rawApiKey(tenantId, modelId).orElse(null);
        if (key != null) {
            ctx.apiKey(key);
            customized = true;
        }
        String base = rawBaseUrl(tenantId, modelId).orElse(null);
        if (base != null) {
            ctx.baseUrl(base);
            customized = true;
        }
        ModelCreationContext context = customized ? ctx.build() : ModelCreationContext.empty();
        return ModelRegistry.resolve(modelId, context);
    }

    private Map<String, Object> toPublic(Map<String, Object> row) {
        Object key = row.get("api_key");
        Object base = row.get("base_url");
        Object en = row.get("enabled");
        return publicView(
                String.valueOf(row.get("tenant_id")),
                String.valueOf(row.get("provider")),
                mask(key == null ? null : String.valueOf(key)),
                base == null ? null : String.valueOf(base),
                en == null || Boolean.TRUE.equals(en) || "t".equalsIgnoreCase(String.valueOf(en)) || "true".equalsIgnoreCase(String.valueOf(en)));
    }

    private Map<String, Object> publicView(String tenantId, String provider, String masked, String baseUrl, boolean enabled) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("tenantId", tenantId);
        m.put("provider", provider);
        m.put("apiKeyMasked", masked);
        m.put("baseUrl", baseUrl);
        m.put("enabled", enabled);
        return m;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
