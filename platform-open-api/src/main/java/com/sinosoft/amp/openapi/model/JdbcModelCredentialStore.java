package com.sinosoft.amp.openapi.model;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 模型凭证存储（provider 级 API Key / baseUrl）。密钥仅库内保存，接口返回打码。
 */
@Repository
public class JdbcModelCredentialStore {

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcModelCredentialStore(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void upsert(String tenantId, String provider, String apiKey, String baseUrl, boolean enabled) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("tenantId", tenantId);
        p.put("provider", provider);
        p.put("apiKey", apiKey);
        p.put("baseUrl", baseUrl);
        p.put("enabled", enabled);
        p.put("updatedAt", Timestamp.from(Instant.now()));
        jdbc.update("""
                INSERT INTO model_credential (tenant_id, provider, api_key, base_url, enabled, updated_at)
                VALUES (:tenantId, :provider, :apiKey, :baseUrl, :enabled, :updatedAt)
                ON CONFLICT (tenant_id, provider) DO UPDATE SET
                  api_key = EXCLUDED.api_key,
                  base_url = EXCLUDED.base_url,
                  enabled = EXCLUDED.enabled,
                  updated_at = EXCLUDED.updated_at
                """, p);
    }

    public boolean delete(String tenantId, String provider) {
        Integer n = jdbc.update("""
                DELETE FROM model_credential WHERE tenant_id = :tenantId AND provider = :provider
                """, Map.of("tenantId", tenantId, "provider", provider));
        return n != null && n > 0;
    }

    public Optional<Map<String, Object>> find(String tenantId, String provider) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT tenant_id, provider, api_key, base_url, enabled, updated_at
                FROM model_credential
                WHERE tenant_id = :tenantId AND provider = :provider
                """, Map.of("tenantId", tenantId, "provider", provider));
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public List<Map<String, Object>> list(String tenantId) {
        return jdbc.queryForList("""
                SELECT tenant_id, provider, api_key, base_url, enabled, updated_at
                FROM model_credential
                WHERE tenant_id = :tenantId
                ORDER BY provider
                """, Map.of("tenantId", tenantId));
    }
}
