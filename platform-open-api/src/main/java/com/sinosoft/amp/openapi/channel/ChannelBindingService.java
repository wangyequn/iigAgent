package com.sinosoft.amp.openapi.channel;

import com.sinosoft.amp.openapi.security.AuthContext;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Channel ↔ Agent 绑定（channel_binding 表）。 */
@Service
public class ChannelBindingService {

    private final NamedParameterJdbcTemplate jdbc;

    public ChannelBindingService(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Map<String, Object> upsert(String channelType, String agentId, String config, String secret) {
        String tenant = AuthContext.requireTenantId();
        jdbc.update("""
                INSERT INTO channel_binding
                  (tenant_id, channel_type, agent_id, config, secret, status, created_at, updated_at)
                VALUES (:t, :ct, :aid, :cfg, :sec, 'ENABLED', :at, :at)
                ON CONFLICT (tenant_id, channel_type, agent_id) DO UPDATE SET
                  config = EXCLUDED.config,
                  secret = EXCLUDED.secret,
                  updated_at = EXCLUDED.updated_at
                """,
                new MapSqlParameterSource()
                        .addValue("t", tenant)
                        .addValue("ct", channelType)
                        .addValue("aid", agentId)
                        .addValue("cfg", config)
                        .addValue("sec", secret)
                        .addValue("at", Timestamp.from(Instant.now())));
        return get(channelType, agentId);
    }

    public Map<String, Object> get(String channelType, String agentId) {
        String tenant = AuthContext.requireTenantId();
        return find(tenant, channelType, agentId).orElseThrow(() ->
                new ChannelException("CHANNEL_BINDING_NOT_FOUND",
                        "binding not found: " + channelType + "/" + agentId));
    }

    public List<Map<String, Object>> list() {
        String tenant = AuthContext.requireTenantId();
        return jdbc.queryForList("""
                SELECT channel_type, agent_id, config, status, updated_at
                FROM channel_binding WHERE tenant_id = :t ORDER BY channel_type, agent_id
                """, new MapSqlParameterSource().addValue("t", tenant));
    }

    /** Channel webhook 用：按租户/类型解析默认 agent。 */
    public Optional<String> resolveAgent(String tenantId, String channelType) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT agent_id FROM channel_binding
                WHERE tenant_id = :t AND lower(channel_type) = lower(:ct) AND status = 'ENABLED'
                ORDER BY updated_at DESC LIMIT 1
                """,
                new MapSqlParameterSource().addValue("t", tenantId).addValue("ct", channelType));
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        Object v = rows.get(0).get("agent_id");
        return v == null ? Optional.empty() : Optional.of(String.valueOf(v));
    }

    private Optional<Map<String, Object>> find(String tenant, String channelType, String agentId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT channel_type, agent_id, config, secret, status, updated_at
                FROM channel_binding WHERE tenant_id = :t AND channel_type = :ct AND agent_id = :aid
                """,
                new MapSqlParameterSource()
                        .addValue("t", tenant).addValue("ct", channelType).addValue("aid", agentId));
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Object> src = rows.get(0);
        Map<String, Object> m = new LinkedHashMap<>(src);
        Object sec = m.get("secret");
        m.put("secret", sec == null ? null : "****");
        return Optional.of(m);
    }
}
