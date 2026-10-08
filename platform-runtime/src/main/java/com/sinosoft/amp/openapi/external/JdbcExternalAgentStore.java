package com.sinosoft.amp.openapi.external;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public class JdbcExternalAgentStore implements ExternalAgentStore {

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcExternalAgentStore(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static Timestamp ts(Instant i) {
        return i == null ? null : Timestamp.from(i);
    }

    private static Instant inst(Timestamp t) {
        return t == null ? null : t.toInstant();
    }

    private ExternalAgentRecord map(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        return new ExternalAgentRecord(
                rs.getString("id"), rs.getString("tenant_id"), rs.getString("name"),
                rs.getString("base_url"), rs.getString("callback_key"), rs.getString("capabilities"),
                rs.getString("status"), inst(rs.getTimestamp("last_heartbeat")),
                inst(rs.getTimestamp("created_at")));
    }

    @Override
    public void upsert(ExternalAgentRecord r) {
        jdbc.update(
                """
                INSERT INTO external_agent
                (id, tenant_id, name, base_url, callback_key, capabilities, status, last_heartbeat, created_at)
                VALUES (:id, :tenantId, :name, :baseUrl, :callbackKey, :capabilities, :status, :hb, :createdAt)
                ON CONFLICT (tenant_id, name) DO UPDATE
                SET base_url = EXCLUDED.base_url,
                    callback_key = EXCLUDED.callback_key,
                    capabilities = EXCLUDED.capabilities,
                    status = EXCLUDED.status,
                    last_heartbeat = EXCLUDED.last_heartbeat
                """,
                new MapSqlParameterSource()
                        .addValue("id", r.id())
                        .addValue("tenantId", r.tenantId())
                        .addValue("name", r.name())
                        .addValue("baseUrl", r.baseUrl())
                        .addValue("callbackKey", r.callbackKey())
                        .addValue("capabilities", r.capabilities())
                        .addValue("status", r.status())
                        .addValue("hb", ts(r.lastHeartbeat()))
                        .addValue("createdAt", ts(r.createdAt() == null ? Instant.now() : r.createdAt())));
    }

    @Override
    public Optional<ExternalAgentRecord> find(String tenantId, String id) {
        return list(tenantId).stream().filter(a -> a.id().equals(id)).findFirst();
    }

    @Override
    public Optional<ExternalAgentRecord> findByName(String tenantId, String name) {
        return list(tenantId).stream().filter(a -> a.name().equals(name)).findFirst();
    }

    @Override
    public List<ExternalAgentRecord> list(String tenantId) {
        return jdbc.query(
                """
                SELECT id, tenant_id, name, base_url, callback_key, capabilities, status, last_heartbeat, created_at
                FROM external_agent WHERE tenant_id = :tenantId ORDER BY name
                """,
                new MapSqlParameterSource().addValue("tenantId", tenantId),
                this::map);
    }

    @Override
    public void heartbeat(String id, Instant at, String status) {
        jdbc.update(
                "UPDATE external_agent SET last_heartbeat = :at, status = :status WHERE id = :id",
                new MapSqlParameterSource()
                        .addValue("at", ts(at == null ? Instant.now() : at))
                        .addValue("status", status)
                        .addValue("id", id));
    }
}
