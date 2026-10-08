package com.sinosoft.amp.openapi.ops;

import com.sinosoft.amp.openapi.security.AuthContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** 审计查询（需 admin scope）。 */
@RestController
@RequestMapping("/api/v1/admin/audit")
public class AdminAuditController {

    private final AuditLogService auditLogService;

    public AdminAuditController(AuditLogService auditLogService) {
        this.auditLogService = auditLogService;
    }

    @GetMapping
    public Map<String, Object> recent(@RequestParam(defaultValue = "50") int limit) {
        AuthContext.requireAdmin();
        String tenant = AuthContext.requireTenantId();
        List<Map<String, Object>> items = auditLogService.recent(tenant, limit);
        return Map.of("tenantId", tenant, "items", items);
    }
}
