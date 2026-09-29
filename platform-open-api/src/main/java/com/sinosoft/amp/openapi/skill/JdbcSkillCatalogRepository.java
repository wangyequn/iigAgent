package com.sinosoft.amp.openapi.skill;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public class JdbcSkillCatalogRepository implements SkillCatalogRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcSkillCatalogRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static Timestamp ts(Instant i) {
        return i == null ? null : Timestamp.from(i);
    }

    private static Instant inst(Timestamp t) {
        return t == null ? null : t.toInstant();
    }

    private SkillRecord map(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        int pct = 100;
        try {
            pct = rs.getInt("rollout_percent");
            if (rs.wasNull()) {
                pct = 100;
            }
        } catch (java.sql.SQLException ignored) {
            pct = 100;
        }
        String env = null;
        try {
            env = rs.getString("visibility_env");
        } catch (java.sql.SQLException ignored) {
            env = null;
        }
        return new SkillRecord(
                rs.getString("id"),
                rs.getString("tenant_id"),
                rs.getString("name"),
                rs.getString("version"),
                rs.getString("description"),
                rs.getString("skill_content"),
                rs.getString("source"),
                rs.getString("status"),
                rs.getString("created_by"),
                inst(rs.getTimestamp("created_at")),
                inst(rs.getTimestamp("updated_at")),
                env,
                pct);
    }

    @Override
    public Optional<SkillRecord> find(String id) {
        return jdbc.query(
                """
                SELECT id, tenant_id, name, version, description, skill_content, source, status,
                       created_by, created_at, updated_at
                FROM skill WHERE id = :id
                """,
                new MapSqlParameterSource().addValue("id", id),
                this::map).stream().findFirst();
    }

    @Override
    public Optional<SkillRecord> findByNameVersion(String tenantId, String name, String version) {
        return jdbc.query(
                """
                SELECT id, tenant_id, name, version, description, skill_content, source, status,
                       created_by, created_at, updated_at
                FROM skill WHERE tenant_id = :tenantId AND name = :name AND version = :version
                """,
                new MapSqlParameterSource()
                        .addValue("tenantId", tenantId)
                        .addValue("name", name)
                        .addValue("version", version),
                this::map).stream().findFirst();
    }

    @Override
    public List<SkillRecord> list(String tenantId) {
        return jdbc.query(
                """
                SELECT id, tenant_id, name, version, description, skill_content, source, status,
                       created_by, created_at, updated_at
                FROM skill WHERE tenant_id = :tenantId ORDER BY name, version
                """,
                new MapSqlParameterSource().addValue("tenantId", tenantId),
                this::map);
    }

    @Override
    public List<SkillRecord> listByStatus(String tenantId, String status) {
        return jdbc.query(
                """
                SELECT id, tenant_id, name, version, description, skill_content, source, status,
                       created_by, created_at, updated_at
                FROM skill WHERE tenant_id = :tenantId AND status = :status ORDER BY name, version
                """,
                new MapSqlParameterSource().addValue("tenantId", tenantId).addValue("status", status),
                this::map);
    }

    @Override
    public void insert(SkillRecord s) {
        jdbc.update(
                """
                INSERT INTO skill
                (id, tenant_id, name, version, description, skill_content, source, status, created_by, created_at, updated_at)
                VALUES
                (:id, :tenantId, :name, :version, :description, :skillContent, :source, :status, :createdBy, :createdAt, :updatedAt)
                ON CONFLICT (tenant_id, name, version) DO UPDATE
                SET description = EXCLUDED.description,
                    skill_content = EXCLUDED.skill_content,
                    source = EXCLUDED.source,
                    status = EXCLUDED.status,
                    updated_at = EXCLUDED.updated_at
                """,
                new MapSqlParameterSource()
                        .addValue("id", s.id())
                        .addValue("tenantId", s.tenantId())
                        .addValue("name", s.name())
                        .addValue("version", s.version())
                        .addValue("description", s.description())
                        .addValue("skillContent", s.skillContent())
                        .addValue("source", s.source())
                        .addValue("status", s.status())
                        .addValue("createdBy", s.createdBy())
                        .addValue("createdAt", ts(s.createdAt() == null ? Instant.now() : s.createdAt()))
                        .addValue("updatedAt", ts(s.updatedAt() == null ? Instant.now() : s.updatedAt())));
    }

    @Override
    public void updateStatus(String id, String status, Instant updatedAt) {
        jdbc.update(
                "UPDATE skill SET status = :status, updated_at = :updatedAt WHERE id = :id",
                new MapSqlParameterSource()
                        .addValue("status", status)
                        .addValue("updatedAt", ts(updatedAt == null ? Instant.now() : updatedAt))
                        .addValue("id", id));
    }

    @Override
    public void recordReview(String reviewId, String skillId, String result, String reviewer, String note) {
        jdbc.update(
                """
                INSERT INTO skill_review (id, skill_id, result, reviewer, note, created_at)
                VALUES (:id, :skillId, :result, :reviewer, :note, :createdAt)
                """,
                new MapSqlParameterSource()
                        .addValue("id", reviewId)
                        .addValue("skillId", skillId)
                        .addValue("result", result)
                        .addValue("reviewer", reviewer)
                        .addValue("note", note)
                        .addValue("createdAt", ts(Instant.now())));
    }

    @Override
    public List<MapReview> listReviews(String skillId) {
        return jdbc.query(
                """
                SELECT id, skill_id, result, reviewer, note, created_at
                FROM skill_review WHERE skill_id = :skillId ORDER BY created_at
                """,
                new MapSqlParameterSource().addValue("skillId", skillId),
                (rs, i) -> new MapReview(
                        rs.getString("id"), rs.getString("skill_id"), rs.getString("result"),
                        rs.getString("reviewer"), rs.getString("note"), inst(rs.getTimestamp("created_at"))));
    }
}
