package com.sinosoft.amp.openapi.web;

import com.sinosoft.amp.common.api.ApiError;
import com.sinosoft.amp.openapi.catalog.CatalogException;
import com.sinosoft.amp.openapi.external.ExternalAgentRecord;
import com.sinosoft.amp.openapi.external.ExternalAgentService;
import com.sinosoft.amp.openapi.security.AuthContext;
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
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * S8 External 最小集 Admin：注册 / 心跳 / 目录。
 */
@RestController
@RequestMapping("/api/v1/admin/external-agents")
public class AdminExternalController {

    private final ExternalAgentService service;

    public AdminExternalController(ExternalAgentService service) {
        this.service = service;
    }

    @GetMapping
    public Map<String, Object> list() {
        String tenant = AuthContext.requireTenantId();
        List<Map<String, Object>> list = new ArrayList<>();
        for (ExternalAgentRecord a : service.listWithHealth(tenant)) {
            list.add(body(a));
        }
        return Map.of("tenantId", tenant, "externalAgents", list);
    }

    @PostMapping
    public Map<String, Object> register(@Valid @RequestBody RegisterRequest req) {
        String tenant = AuthContext.requireTenantId();
        ExternalAgentRecord rec = service.register(
                tenant, req.name(), req.baseUrl(), req.callbackKey(), req.capabilities());
        return body(rec);
    }

    @PostMapping("/{id}/heartbeat")
    public Map<String, Object> heartbeat(@PathVariable String id) {
        String tenant = AuthContext.requireTenantId();
        return body(service.heartbeat(tenant, id));
    }

    private Map<String, Object> body(ExternalAgentRecord a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", a.id());
        m.put("name", a.name());
        m.put("baseUrl", a.baseUrl());
        m.put("capabilities", a.capabilities());
        m.put("status", a.status());
        m.put("lastHeartbeat", a.lastHeartbeat());
        return m;
    }

    @ExceptionHandler(CatalogException.class)
    public ResponseEntity<ApiError> handle(CatalogException e) {
        HttpStatus status = switch (e.code()) {
            case "EXTERNAL_NOT_FOUND" -> HttpStatus.NOT_FOUND;
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

    public record RegisterRequest(
            @NotBlank String name,
            @NotBlank String baseUrl,
            String callbackKey,
            String capabilities
    ) {
    }
}
