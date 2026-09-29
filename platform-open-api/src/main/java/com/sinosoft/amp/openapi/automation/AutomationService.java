package com.sinosoft.amp.openapi.automation;

import com.sinosoft.amp.openapi.security.AuthContext;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * S13 Automation 最小集：Webhook 触发登记 + 手动 fire；
 * Cron 调度用 Spring @Scheduled 扫描 ENABLED 且 next_due_at 到期的记录（db 锁后续加）。
 */
@Service
public class AutomationService {

    private final NamedParameterJdbcTemplate jdbc;

    public AutomationService(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Map<String, Object> create(String name, String type, String cronExpr, String secret,
                                      String targetWorkflowId, String payloadJson) {
        String tenant = AuthContext.requireTenantId();
        String id = UUID.randomUUID().toString().replace("-", "");
        jdbc.update("""
                INSERT INTO automation_trigger
                  (id, tenant_id, name, type, cron_expr, secret, target_workflow_id, payload,
                   status, next_due_at, created_at, updated_at)
                VALUES (:id, :t, :name, :type, :cron, :secret, :wf, :payload, 'ENABLED', :at, :at, :at)
                """,
                new MapSqlParameterSource()
                        .addValue("id", id)
                        .addValue("t", tenant)
                        .addValue("name", name)
                        .addValue("type", type == null ? "WEBHOOK" : type.toUpperCase(java.util.Locale.ROOT))
                        .addValue("cron", cronExpr)
                        .addValue("secret", secret)
                        .addValue("wf", targetWorkflowId)
                        .addValue("payload", payloadJson)
                        .addValue("at", Timestamp.from(Instant.now())));
        return get(id);
    }

    public Map<String, Object> get(String id) {
        String tenant = AuthContext.requireTenantId();
        return list().stream()
                .filter(m -> id.equals(String.valueOf(m.get("id"))))
                .findFirst()
                .orElseThrow(() -> new AutomationException("AUTOMATION_NOT_FOUND", "automation not found: " + id));
    }

    public List<Map<String, Object>> list() {
        String tenant = AuthContext.requireTenantId();
        return jdbc.queryForList("""
                SELECT id, name, type, cron_expr, target_workflow_id, status, next_due_at, created_at
                FROM automation_trigger WHERE tenant_id = :t ORDER BY created_at DESC
                """, new MapSqlParameterSource().addValue("t", tenant));
    }

    /** Webhook 入口：校验 secret 并返回待执行目标（由调用方启 Workflow Run）。 */
    public Map<String, Object> fireWebhook(String id, String secret, String body) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT id, tenant_id, name, secret, target_workflow_id, payload, status
                FROM automation_trigger WHERE id = :id
                """, new MapSqlParameterSource().addValue("id", id));
        if (rows.isEmpty()) {
            throw new AutomationException("AUTOMATION_NOT_FOUND", "automation not found: " + id);
        }
        Map<String, Object> row = rows.get(0);
        if (!"ENABLED".equalsIgnoreCase(String.valueOf(row.get("status")))) {
            throw new AutomationException("AUTOMATION_DISABLED", "automation disabled: " + id);
        }
        String expect = row.get("secret") == null ? "" : String.valueOf(row.get("secret"));
        if (!expect.isBlank() && !expect.equals(secret)) {
            throw new AutomationException("CHANNEL_SIGNATURE_INVALID", "automation secret mismatch");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("automationId", id);
        out.put("tenantId", row.get("tenant_id"));
        out.put("workflowId", row.get("target_workflow_id"));
        out.put("payload", row.get("payload"));
        out.put("body", body);
        jdbc.update("""
                UPDATE automation_trigger SET last_fired_at = :at, updated_at = :at WHERE id = :id
                """, new MapSqlParameterSource().addValue("id", id).addValue("at", Timestamp.from(Instant.now())));
        return out;
    }

    public void disable(String id) {
        String tenant = AuthContext.requireTenantId();
        jdbc.update("""
                UPDATE automation_trigger SET status = 'DISABLED', updated_at = :at
                WHERE id = :id AND tenant_id = :t
                """,
                new MapSqlParameterSource().addValue("id", id).addValue("t", tenant)
                        .addValue("at", Timestamp.from(Instant.now())));
    }

    /** 供调度器扫描 CRON 到期项（简化：next_due_at 为空或已过期）。 */
    public List<Map<String, Object>> dueCronTriggers() {
        return jdbc.queryForList("""
                SELECT id, tenant_id, name, cron_expr, target_workflow_id, payload
                FROM automation_trigger
                WHERE type = 'CRON' AND status = 'ENABLED'
                  AND (next_due_at IS NULL OR next_due_at <= :at)
                """, new MapSqlParameterSource().addValue("at", Timestamp.from(Instant.now())));
    }
}
