package com.sinosoft.amp.openapi.skill;

import com.sinosoft.amp.openapi.catalog.CatalogException;
import com.sinosoft.amp.openapi.security.AuthContext;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.zip.CRC32;

/**
 * S15：技能灰度全链路 —— CANARY / GA / ROLLBACK。
 * 稳定哈希百分比：userId 对 skillId 取 CRC32 % 100 &lt; percent 则命中。
 */
@Service
public class SkillRolloutService {

    public static final String STAGE_CANARY = "CANARY";
    public static final String STAGE_GA = "GA";
    public static final String STAGE_ROLLBACK = "ROLLBACK";

    private final NamedParameterJdbcTemplate jdbc;

    public SkillRolloutService(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Map<String, Object> startCanary(String skillId, int percent, String env, String tenantWhitelist) {
        return upsert(skillId, STAGE_CANARY, clamp(percent), env, tenantWhitelist);
    }

    public Map<String, Object> promoteGa(String skillId) {
        return upsert(skillId, STAGE_GA, 100, null, null);
    }

    public Map<String, Object> rollback(String skillId) {
        return upsert(skillId, STAGE_ROLLBACK, 0, null, null);
    }

    public Map<String, Object> get(String skillId) {
        String tenant = AuthContext.requireTenantId();
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT id, skill_id, stage, rollout_percent, visibility_env, tenant_whitelist,
                       status, created_at, updated_at
                FROM skill_rollout WHERE tenant_id = :t AND skill_id = :s
                ORDER BY updated_at DESC LIMIT 1
                """,
                new MapSqlParameterSource().addValue("t", tenant).addValue("s", skillId));
        if (rows.isEmpty()) {
            throw new CatalogException("SKILL_ROLLOUT_NOT_FOUND", "rollout not found: " + skillId);
        }
        return rows.get(0);
    }

    public List<Map<String, Object>> list() {
        String tenant = AuthContext.requireTenantId();
        return jdbc.queryForList("""
                SELECT skill_id, stage, rollout_percent, visibility_env, status, updated_at
                FROM skill_rollout WHERE tenant_id = :t ORDER BY updated_at DESC
                """, new MapSqlParameterSource().addValue("t", tenant));
    }

    /** 判断某用户是否命中当前灰度。 */
    public boolean hit(String skillId, String userId) {
        String tenant = AuthContext.requireTenantId();
        Map<String, Object> r;
        try {
            r = get(skillId);
        } catch (CatalogException e) {
            return true; // 无灰度配置 = 默认全量（兼容旧行为）
        }
        String status = String.valueOf(r.get("status"));
        String stage = String.valueOf(r.get("stage"));
        if ("DISABLED".equalsIgnoreCase(status) || STAGE_ROLLBACK.equals(stage)) {
            return false;
        }
        if (STAGE_GA.equals(stage)) {
            return true;
        }
        int percent = r.get("rollout_percent") == null ? 0 : ((Number) r.get("rollout_percent")).intValue();
        return stableHit(skillId, userId, percent);
    }

    static boolean stableHit(String skillId, String userId, int percent) {
        if (percent >= 100) {
            return true;
        }
        if (percent <= 0) {
            return false;
        }
        CRC32 crc = new CRC32();
        crc.update((skillId + ":" + (userId == null ? "" : userId)).getBytes(StandardCharsets.UTF_8));
        return (int) (crc.getValue() % 100) < percent;
    }

    private Map<String, Object> upsert(String skillId, String stage, int percent, String env, String tenantWhitelist) {
        String tenant = AuthContext.requireTenantId();
        String id = UUID.randomUUID().toString().replace("-", "");
        Instant now = Instant.now();
        jdbc.update("""
                INSERT INTO skill_rollout
                  (id, tenant_id, skill_id, stage, rollout_percent, visibility_env, tenant_whitelist,
                   status, created_at, updated_at)
                VALUES (:id, :t, :s, :stage, :pct, :env, :tw, 'ACTIVE', :at, :at)
                """,
                new MapSqlParameterSource()
                        .addValue("id", id)
                        .addValue("t", tenant)
                        .addValue("s", skillId)
                        .addValue("stage", stage)
                        .addValue("pct", percent)
                        .addValue("env", env)
                        .addValue("tw", tenantWhitelist)
                        .addValue("at", Timestamp.from(now)));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("rolloutId", id);
        out.put("skillId", skillId);
        out.put("stage", stage);
        out.put("rolloutPercent", percent);
        out.put("status", "ACTIVE");
        return out;
    }

    private static int clamp(int p) {
        return Math.max(0, Math.min(100, p));
    }

    public static String normalizeStage(String s) {
        return s == null ? "" : s.trim().toUpperCase(Locale.ROOT);
    }
}
