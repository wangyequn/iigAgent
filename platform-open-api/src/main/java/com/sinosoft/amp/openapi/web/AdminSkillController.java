package com.sinosoft.amp.openapi.web;

import com.sinosoft.amp.common.api.ApiError;
import com.sinosoft.amp.openapi.catalog.CatalogException;
import com.sinosoft.amp.openapi.security.AuthContext;
import com.sinosoft.amp.openapi.skill.SkillAdminService;
import com.sinosoft.amp.openapi.skill.SkillCatalogRepository;
import com.sinosoft.amp.openapi.skill.SkillRecord;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * S5 技能市场 Admin API。
 * 设计 §6.2：POST /admin/skills、POST /admin/skills/{id}:approve → /api/v1/admin/skills...
 */
@RestController
@RequestMapping("/api/v1/admin/skills")
public class AdminSkillController {

    private final SkillAdminService skills;

    public AdminSkillController(SkillAdminService skills) {
        this.skills = skills;
    }

    @GetMapping
    public Map<String, Object> list(@RequestParam(required = false) String status) {
        String tenant = AuthContext.requireTenantId();
        List<SkillRecord> rows = status == null || status.isBlank()
                ? skills.list(tenant)
                : skills.list(tenant).stream().filter(s -> s.status().equalsIgnoreCase(status)).toList();
        boolean approvedOnly = "APPROVED".equalsIgnoreCase(status);
        if (approvedOnly) {
            rows = skills.listVisible(tenant);
        }
        List<Map<String, Object>> list = new ArrayList<>();
        for (SkillRecord s : rows) {
            list.add(body(s, false));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tenantId", tenant);
        out.put("skills", list);
        return out;
    }

    @GetMapping("/visible")
    public Map<String, Object> visible() {
        String tenant = AuthContext.requireTenantId();
        List<Map<String, Object>> list = new ArrayList<>();
        for (SkillRecord s : skills.listVisible(tenant)) {
            list.add(body(s, true));
        }
        return Map.of("tenantId", tenant, "skills", list, "note", "仅 APPROVED 可见（只读市场）");
    }

    @PostMapping
    public Map<String, Object> upload(@Valid @RequestBody UploadRequest request) {
        String tenant = AuthContext.requireTenantId();
        SkillRecord rec = skills.upload(tenant, request.name(), request.version(), request.description(),
                request.skillContent(), AuthContext.get() == null ? "api" : AuthContext.get().tenantId());
        return body(rec, true);
    }

    @GetMapping("/{id}")
    public Map<String, Object> get(@PathVariable String id) {
        String tenant = AuthContext.requireTenantId();
        SkillRecord s = skills.get(id);
        if (!s.tenantId().equals(tenant)) {
            throw new CatalogException("FORBIDDEN", "skill tenant mismatch");
        }
        Map<String, Object> out = body(s, true);
        out.put("reviews", skills.reviews(id).stream().map(r -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("result", r.result());
            m.put("reviewer", r.reviewer());
            m.put("note", r.note());
            m.put("createdAt", r.createdAt());
            return m;
        }).toList());
        return out;
    }

    @PostMapping("/{id}/approve")
    public Map<String, Object> approve(@PathVariable String id, @RequestBody(required = false) ReviewRequest request) {
        String tenant = AuthContext.requireTenantId();
        String reviewer = request == null || request.reviewer() == null ? "console" : request.reviewer();
        String note = request == null ? null : request.note();
        SkillRecord rec = skills.approve(tenant, id, reviewer, note);
        return body(rec, true);
    }

    @PostMapping("/{id}:approve")
    public Map<String, Object> approveColon(@PathVariable String id, @RequestBody(required = false) ReviewRequest request) {
        return approve(id, request);
    }

    @PostMapping("/{id}/reject")
    public Map<String, Object> reject(@PathVariable String id, @RequestBody(required = false) ReviewRequest request) {
        String tenant = AuthContext.requireTenantId();
        String reviewer = request == null || request.reviewer() == null ? "console" : request.reviewer();
        String note = request == null ? null : request.note();
        SkillRecord rec = skills.reject(tenant, id, reviewer, note);
        return body(rec, true);
    }

    @PostMapping("/{id}/archive")
    public Map<String, Object> archive(@PathVariable String id) {
        String tenant = AuthContext.requireTenantId();
        SkillRecord rec = skills.archive(tenant, id);
        return body(rec, true);
    }

    private Map<String, Object> body(SkillRecord s, boolean withContent) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", s.id());
        m.put("name", s.name());
        m.put("version", s.version());
        m.put("description", s.description());
        m.put("status", s.status());
        m.put("source", s.source());
        m.put("createdBy", s.createdBy());
        m.put("createdAt", s.createdAt());
        m.put("updatedAt", s.updatedAt());
        if (withContent) {
            m.put("skillContent", s.skillContent());
        }
        return m;
    }

    @ExceptionHandler(CatalogException.class)
    public ResponseEntity<ApiError> handleCatalog(CatalogException e) {
        HttpStatus status = switch (e.code()) {
            case "SKILL_NOT_FOUND" -> HttpStatus.NOT_FOUND;
            case "FORBIDDEN" -> HttpStatus.FORBIDDEN;
            default -> HttpStatus.BAD_REQUEST;
        };
        return ResponseEntity.status(status).body(ApiError.of(e.code(), e.getMessage(), requestId()));
    }

    private static String requestId() {
        ServletRequestAttributes attrs =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attrs != null && attrs.getRequest().getHeader("X-Request-Id") != null) {
            return attrs.getRequest().getHeader("X-Request-Id");
        }
        return UUID.randomUUID().toString();
    }

    public record UploadRequest(
            @NotBlank String name,
            String version,
            String description,
            @NotBlank String skillContent
    ) {
    }

    public record ReviewRequest(String reviewer, String note) {
    }
}
