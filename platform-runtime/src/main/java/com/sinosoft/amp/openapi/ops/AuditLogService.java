package com.sinosoft.amp.openapi.ops;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * P0 审计落库（写失败不阻断业务）。
 */
@Service
public class AuditLogService {

    private static final Logger log = LoggerFactory.getLogger(AuditLogService.class);

    private final NamedParameterJdbcTemplate jdbc;

    public AuditLogService(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
        ToolCallAuditor.bind(this);
    }

    public void record(String tenantId, String actor, String action,
                       String resourceType, String resourceId,
                       String method, String path, String detail,
                       Integer statusCode, Long durationMs) {
        try {
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("tenantId", tenantId == null ? "" : tenantId);
            params.put("actor", actor);
            params.put("action", action == null ? "UNKNOWN" : action);
            params.put("resourceType", resourceType);
            params.put("resourceId", resourceId);
            params.put("method", method);
            params.put("path", path);
            params.put("detail", detail);
            params.put("statusCode", statusCode);
            params.put("durationMs", durationMs);
            params.put("createdAt", Timestamp.from(Instant.now()));
            jdbc.update("""
                    INSERT INTO audit_log
                      (tenant_id, actor, action, resource_type, resource_id, method, path, detail, status_code, duration_ms, created_at)
                    VALUES
                      (:tenantId, :actor, :action, :resourceType, :resourceId, :method, :path, :detail, :statusCode, :durationMs, :createdAt)
                    """, params);
        } catch (Exception e) {
            log.warn("audit write failed action={} path={} err={}", action, path, e.toString());
        }
    }

    public List<Map<String, Object>> recent(String tenantId, int limit) {
        return recent(tenantId, limit, null, null, null);
    }

    /** 支持按 toolName（resource_id）/ action 过滤，便于工具详情抽屉联动审计。 */
    public List<Map<String, Object>> recent(String tenantId, int limit,
                                            String toolName, String resourceId, String action) {
        int size = Math.max(1, Math.min(limit <= 0 ? 50 : limit, 200));
        StringBuilder sql = new StringBuilder("""
                SELECT id, tenant_id, actor, action, resource_type, resource_id, method, path,
                       detail, status_code, duration_ms, created_at
                FROM audit_log
                WHERE tenant_id = :tenantId
                """);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("tenantId", tenantId == null ? "" : tenantId);
        params.put("limit", size);
        if (toolName != null && !toolName.isBlank()) {
            sql.append(" AND (resource_id = :toolName OR detail LIKE :toolLike)");
            params.put("toolName", toolName);
            params.put("toolLike", "%" + toolName + "%");
        }
        if (resourceId != null && !resourceId.isBlank()) {
            sql.append(" AND resource_id = :resourceId");
            params.put("resourceId", resourceId);
        }
        if (action != null && !action.isBlank()) {
            sql.append(" AND action = :action");
            params.put("action", action);
        }
        sql.append(" ORDER BY id DESC LIMIT :limit");
        return jdbc.queryForList(sql.toString(), params);
    }
}
