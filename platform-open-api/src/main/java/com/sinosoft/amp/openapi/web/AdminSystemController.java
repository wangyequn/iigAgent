package com.sinosoft.amp.openapi.web;

import com.sinosoft.amp.common.api.ApiError;
import com.sinosoft.amp.openapi.catalog.CatalogException;
import com.sinosoft.amp.openapi.governance.BusinessSystemRecord;
import com.sinosoft.amp.openapi.governance.GovernanceService;
import com.sinosoft.amp.openapi.security.AuthContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 业务系统目录：智能体 / 工具按 systemCode 划分归属。
 */
@RestController
@RequestMapping("/api/v1/admin/systems")
public class AdminSystemController {

    private final GovernanceService governance;

    public AdminSystemController(GovernanceService governance) {
        this.governance = governance;
    }

    @GetMapping
    public Map<String, Object> list() {
        String tenant = AuthContext.requireTenantId();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (BusinessSystemRecord s : governance.listBusinessSystems(tenant)) {
            rows.add(row(s));
        }
        return Map.of("tenantId", tenant, "systems", rows);
    }

    @GetMapping("/{code}")
    public Map<String, Object> get(@PathVariable String code) {
        String tenant = AuthContext.requireTenantId();
        return row(governance.findBusinessSystem(tenant, code));
    }

    @PostMapping
    public Map<String, Object> create(@Valid @RequestBody SystemRequest request) {
        String tenant = AuthContext.requireTenantId();
        BusinessSystemRecord rec = governance.upsertBusinessSystem(
                tenant, request.systemCode(), request.name(), request.owner(),
                request.contact(), request.description(), request.status());
        return row(rec);
    }

    @PutMapping("/{code}")
    public Map<String, Object> update(@PathVariable String code, @RequestBody SystemRequest request) {
        String tenant = AuthContext.requireTenantId();
        BusinessSystemRecord existing = governance.findBusinessSystem(tenant, code);
        BusinessSystemRecord rec = governance.upsertBusinessSystem(
                tenant, code,
                request.name() == null || request.name().isBlank() ? existing.name() : request.name(),
                request.owner() == null ? existing.owner() : request.owner(),
                request.contact() == null ? existing.contact() : request.contact(),
                request.description() == null ? existing.description() : request.description(),
                request.status() == null ? existing.status() : request.status());
        return row(rec);
    }

    @DeleteMapping("/{code}")
    public Map<String, Object> delete(@PathVariable String code) {
        String tenant = AuthContext.requireTenantId();
        governance.deleteBusinessSystem(tenant, code);
        return Map.of("deleted", true, "systemCode", code);
    }

    private Map<String, Object> row(BusinessSystemRecord s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("systemCode", s.systemCode());
        m.put("name", s.name());
        m.put("owner", s.owner());
        m.put("contact", s.contact());
        m.put("description", s.description());
        m.put("status", s.status());
        m.put("tenantId", s.tenantId());
        return m;
    }

    @ExceptionHandler(CatalogException.class)
    public ResponseEntity<ApiError> handle(CatalogException e) {
        HttpStatus status = switch (e.code()) {
            case "SYSTEM_NOT_FOUND" -> HttpStatus.NOT_FOUND;
            case "SYSTEM_IN_USE" -> HttpStatus.CONFLICT;
            case "SYSTEM_CODE_INVALID", "SYSTEM_NAME_REQUIRED", "SYSTEM_STATUS_INVALID" -> HttpStatus.BAD_REQUEST;
            default -> HttpStatus.BAD_REQUEST;
        };
        return ResponseEntity.status(status).body(ApiError.of(e.code(), e.getMessage(), UUID.randomUUID().toString()));
    }

    public record SystemRequest(
            String systemCode,
            @NotBlank String name,
            String owner,
            String contact,
            String description,
            String status) {
    }
}
