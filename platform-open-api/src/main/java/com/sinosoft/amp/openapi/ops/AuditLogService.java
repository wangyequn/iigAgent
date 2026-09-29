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
        int size = Math.max(1, Math.min(limit <= 0 ? 50 : limit, 200));
        return jdbc.queryForList("""
                SELECT id, tenant_id, actor, action, resource_type, resource_id, method, path,
                       detail, status_code, duration_ms, created_at
                FROM audit_log
                WHERE tenant_id = :tenantId
                ORDER BY id DESC
                LIMIT :limit
                """, Map.of("tenantId", tenantId == null ? "" : tenantId, "limit", size));
    }
}
