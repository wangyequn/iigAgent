package com.sinosoft.amp.openapi.model;

import com.sinosoft.amp.openapi.security.AuthContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** 模型凭证管理（admin scope）。返回密钥一律打码。 */
@RestController
@RequestMapping("/api/v1/admin/model-credentials")
public class AdminModelCredentialController {

    private final ModelCredentialService credentials;

    public AdminModelCredentialController(ModelCredentialService credentials) {
        this.credentials = credentials;
    }

    @GetMapping
    public Map<String, Object> list() {
        AuthContext.requireAdmin();
        String tenant = AuthContext.requireTenantId();
        List<Map<String, Object>> items = credentials.listMasked(tenant);
        return Map.of("tenantId", tenant, "items", items);
    }

    @PostMapping
    public Map<String, Object> upsert(@Valid @RequestBody UpsertRequest request) {
        AuthContext.requireAdmin();
        String tenant = AuthContext.requireTenantId();
        return credentials.upsert(tenant, request.provider(), request.apiKey(), request.baseUrl());
    }

    @DeleteMapping("/{provider}")
    public Map<String, Object> delete(@PathVariable String provider) {
        AuthContext.requireAdmin();
        String tenant = AuthContext.requireTenantId();
        boolean ok = credentials.delete(tenant, provider);
        return Map.of("deleted", ok, "provider", provider);
    }

    public record UpsertRequest(
            @NotBlank String provider,
            @NotBlank String apiKey,
            String baseUrl
    ) {
    }
}
