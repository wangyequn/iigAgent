package com.sinosoft.amp.openapi.catalog;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public class JdbcAgentCatalogRepository implements AgentCatalogRepository {

    private static final RowMapper<AgentRecord> AGENT_MAPPER = (rs, i) -> new AgentRecord(
            rs.getString("tenant_id"),
            rs.getString("agent_id"),
            rs.getString("name"),
            AgentStatus.valueOf(rs.getString("status")),
            toInstant(rs.getTimestamp("created_at"))
    );

    private static final RowMapper<AgentVersionRecord> VERSION_MAPPER = (rs, i) -> new AgentVersionRecord(
            rs.getString("tenant_id"),
            rs.getString("agent_id"),
            rs.getInt("version"),
            VersionStatus.valueOf(rs.getString("status")),
            rs.getString("model_id"),
            rs.getString("sys_prompt"),
            rs.getString("workspace"),
            toInstant(rs.getTimestamp("created_at")),
            toInstant(rs.getTimestamp("published_at"))
    );

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcAgentCatalogRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static Instant toInstant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }

    private static Timestamp toTs(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private static Timestamp nowTs() {
        return Timestamp.from(Instant.now());
    }

    @Override
    public Optional<AgentRecord> findAgent(String tenantId, String agentId) {
        List<AgentRecord> rows = jdbc.query(
                """
                SELECT tenant_id, agent_id, name, status, created_at
                FROM agent
                WHERE tenant_id = :tenantId AND agent_id = :agentId
                """,
                new MapSqlParameterSource()
                        .addValue("tenantId", tenantId)
                        .addValue("agentId", agentId),
                AGENT_MAPPER);
        return rows.stream().findFirst();
    }

    @Override
    public List<AgentRecord> listAgents(String tenantId) {
        return jdbc.query(
                """
                SELECT tenant_id, agent_id, name, status, created_at
                FROM agent
                WHERE tenant_id = :tenantId
                ORDER BY agent_id
                """,
                new MapSqlParameterSource().addValue("tenantId", tenantId),
                AGENT_MAPPER);
    }

    @Override
    public void upsertAgent(AgentRecord agent) {
        jdbc.update(
                """
                INSERT INTO agent (tenant_id, agent_id, name, status, created_at)
                VALUES (:tenantId, :agentId, :name, :status, :createdAt)
                ON CONFLICT (tenant_id, agent_id) DO UPDATE
                SET name = EXCLUDED.name,
                    status = EXCLUDED.status
                """,
                new MapSqlParameterSource()
                        .addValue("tenantId", agent.tenantId())
                        .addValue("agentId", agent.agentId())
                        .addValue("name", agent.name())
                        .addValue("status", agent.status().name())
                        .addValue("createdAt", toTs(agent.createdAt() == null ? Instant.now() : agent.createdAt())));
    }

    @Override
    public Optional<AgentVersionRecord> findVersion(String tenantId, String agentId, int version) {
        List<AgentVersionRecord> rows = jdbc.query(
                """
                SELECT tenant_id, agent_id, version, status, model_id, sys_prompt, workspace, created_at, published_at
                FROM agent_version
                WHERE tenant_id = :tenantId AND agent_id = :agentId AND version = :version
                """,
                new MapSqlParameterSource()
                        .addValue("tenantId", tenantId)
                        .addValue("agentId", agentId)
                        .addValue("version", version),
                VERSION_MAPPER);
        return rows.stream().findFirst();
    }

    @Override
    public List<AgentVersionRecord> listVersions(String tenantId, String agentId) {
        return jdbc.query(
                """
                SELECT tenant_id, agent_id, version, status, model_id, sys_prompt, workspace, created_at, published_at
                FROM agent_version
                WHERE tenant_id = :tenantId AND agent_id = :agentId
                ORDER BY version
                """,
                new MapSqlParameterSource()
                        .addValue("tenantId", tenantId)
                        .addValue("agentId", agentId),
                VERSION_MAPPER);
    }

    @Override
    public void upsertVersion(AgentVersionRecord version) {
        jdbc.update(
                """
                INSERT INTO agent_version
                (tenant_id, agent_id, version, status, model_id, sys_prompt, workspace, created_at, published_at)
                VALUES
                (:tenantId, :agentId, :version, :status, :modelId, :sysPrompt, :workspace, :createdAt, :publishedAt)
                ON CONFLICT (tenant_id, agent_id, version) DO UPDATE
                SET status = EXCLUDED.status,
                    model_id = EXCLUDED.model_id,
                    sys_prompt = EXCLUDED.sys_prompt,
                    workspace = EXCLUDED.workspace,
                    published_at = EXCLUDED.published_at
                """,
                new MapSqlParameterSource()
                        .addValue("tenantId", version.tenantId())
                        .addValue("agentId", version.agentId())
                        .addValue("version", version.version())
                        .addValue("status", version.status().name())
                        .addValue("modelId", version.modelId())
                        .addValue("sysPrompt", version.sysPrompt())
                        .addValue("workspace", version.workspace())
                        .addValue("createdAt", toTs(version.createdAt() == null ? Instant.now() : version.createdAt()))
                        .addValue("publishedAt", toTs(version.publishedAt())));
    }

    @Override
    public void updateVersionStatus(String tenantId, String agentId, int version, VersionStatus status) {
        jdbc.update(
                """
                UPDATE agent_version
                SET status = :status,
                    published_at = CASE WHEN :status = 'PUBLISHED' THEN COALESCE(published_at, :now) ELSE published_at END
                WHERE tenant_id = :tenantId AND agent_id = :agentId AND version = :version
                """,
                new MapSqlParameterSource()
                        .addValue("status", status.name())
                        .addValue("now", nowTs())
                        .addValue("tenantId", tenantId)
                        .addValue("agentId", agentId)
                        .addValue("version", version));
    }

    @Override
    public Optional<EndpointRelease> findRelease(String tenantId, String agentId) {
        List<EndpointRelease> rows = jdbc.query(
                """
                SELECT tenant_id, agent_id, release_version, updated_at
                FROM endpoint_release
                WHERE tenant_id = :tenantId AND agent_id = :agentId
                """,
                new MapSqlParameterSource()
                        .addValue("tenantId", tenantId)
                        .addValue("agentId", agentId),
                (rs, i) -> new EndpointRelease(
                        rs.getString("tenant_id"),
                        rs.getString("agent_id"),
                        rs.getInt("release_version"),
                        toInstant(rs.getTimestamp("updated_at"))));
        return rows.stream().findFirst();
    }

    @Override
    public void upsertRelease(String tenantId, String agentId, int releaseVersion) {
        jdbc.update(
                """
                INSERT INTO endpoint_release (tenant_id, agent_id, release_version, updated_at)
                VALUES (:tenantId, :agentId, :releaseVersion, :updatedAt)
                ON CONFLICT (tenant_id, agent_id) DO UPDATE
                SET release_version = EXCLUDED.release_version,
                    updated_at = EXCLUDED.updated_at
                """,
                new MapSqlParameterSource()
                        .addValue("tenantId", tenantId)
                        .addValue("agentId", agentId)
                        .addValue("releaseVersion", releaseVersion)
                        .addValue("updatedAt", nowTs()));
    }
}
