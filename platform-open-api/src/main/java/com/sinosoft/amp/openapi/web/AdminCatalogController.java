package com.sinosoft.amp.openapi.web;

import com.sinosoft.amp.openapi.agent.CatalogRuntimeService;
import com.sinosoft.amp.openapi.catalog.AgentRecord;
import com.sinosoft.amp.openapi.catalog.AgentVersionRecord;
import com.sinosoft.amp.openapi.security.AuthContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import com.sinosoft.amp.common.api.ApiError;
import com.sinosoft.amp.openapi.agent.AgentInstanceCache;
import com.sinosoft.amp.openapi.catalog.CatalogException;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * S3 管理 API（挂在 /api/v1 下以复用 API Key 过滤器）。
 * 设计 §6.2：POST /admin/agents、publish → 这里为 /api/v1/admin/agents...
 */
@RestController
@RequestMapping("/api/v1/admin")
public class AdminCatalogController {

    private final CatalogRuntimeService catalogRuntime;
    private final AgentInstanceCache instanceCache;

    public AdminCatalogController(CatalogRuntimeService catalogRuntime, AgentInstanceCache instanceCache) {
        this.catalogRuntime = catalogRuntime;
        this.instanceCache = instanceCache;
    }

    @PostMapping("/agents")
    public Map<String, Object> createAgent(@Valid @RequestBody CreateAgentRequest request) {
        String tenant = AuthContext.requireTenantId();
        AgentRecord agent = catalogRuntime.createAgent(tenant, request.agentId(), request.name(), request.systemCode());
        return agentBody(agent, null);
    }

    @PutMapping("/agents/{agentId}")
    public Map<String, Object> updateAgent(
            @PathVariable String agentId,
            @RequestBody UpdateAgentRequest request) {
        String tenant = AuthContext.requireTenantId();
        AuthContext.requireAgent(agentId);
        AgentRecord agent = catalogRuntime.updateAgent(tenant, agentId, request.name(), request.enabled());
        instanceCache.evictTenant(tenant);
        return agentBody(agent, null);
    }

    @DeleteMapping("/agents/{agentId}")
    public Map<String, Object> deleteAgent(@PathVariable String agentId) {
        String tenant = AuthContext.requireTenantId();
        AuthContext.requireAgent(agentId);
        catalogRuntime.deleteAgent(tenant, agentId);
        instanceCache.evictTenant(tenant);
        return Map.of("deleted", true, "agentId", agentId);
    }

    @PostMapping("/agents/{agentId}/versions")
    public Map<String, Object> createVersion(
            @PathVariable String agentId,
            @Valid @RequestBody CreateVersionRequest request) {
        String tenant = AuthContext.requireTenantId();
        AuthContext.requireAgent(agentId);
        AgentVersionRecord version = catalogRuntime.createVersion(
                tenant, agentId, request.version(), request.modelId(), request.sysPrompt(), request.workspace());
        return versionBody(version, null);
    }

    /**
     * 发布并切流（B2）：新版本实例 build 成功后切 Endpoint 指针，旧版进入排空。
     * 路径兼容设计稿的 :publish 风格：POST .../versions/{version}:publish
     */
    @PostMapping("/agents/{agentId}/versions/{version}")
    public Map<String, Object> publish(
            @PathVariable String agentId,
            @PathVariable String version) {
        return publishPath(agentId, version);
    }

    @PostMapping("/agents/{agentId}/versions/{version}:publish")
    public Map<String, Object> publishWithColon(
            @PathVariable String agentId,
            @PathVariable String version) {
        return publishPath(agentId, version);
    }

    @PostMapping("/agents/{agentId}/versions/{version}/publish")
    public Map<String, Object> publishSlash(
            @PathVariable String agentId,
            @PathVariable String version) {
        return publishPath(agentId, version);
    }

    @GetMapping("/runtime/instances")
    public Map<String, Object> runtimeInstances() {
        AuthContext.requireTenantId();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("count", instanceCache.size());
        body.put("instances", instanceCache.snapshot());
        return body;
    }

    private Map<String, Object> publishPath(String agentId, String rawVersion) {
        String tenant = AuthContext.requireTenantId();
        AuthContext.requireAgent(agentId);
        int version;
        try {
            version = Integer.parseInt(rawVersion.replace(":publish", ""));
        } catch (NumberFormatException e) {
            throw new CatalogException("VERSION_INVALID", "invalid version: " + rawVersion);
        }
        CatalogRuntimeService.PublishResult result = catalogRuntime.publishAndSwitch(tenant, agentId, version);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("tenantId", result.tenantId());
        body.put("agentId", result.agentId());
        body.put("releaseVersion", result.releaseVersion());
        body.put("previousVersion", result.previousVersion());
        body.put("instanceKey", result.instanceKey());
        body.put("status", "RELEASE_SWITCHED");
        return body;
    }

    private Map<String, Object> agentBody(AgentRecord agent, Integer release) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("tenantId", agent.tenantId());
        body.put("id", agent.agentId());
        body.put("name", agent.name());
        body.put("status", agent.status().name());
        body.put("releaseVersion", release);
        body.put("systemCode", agent.systemCode());
        return body;
    }

    private Map<String, Object> versionBody(AgentVersionRecord version, Integer release) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("tenantId", version.tenantId());
        body.put("agentId", version.agentId());
        body.put("version", version.version());
        body.put("status", version.status().name());
        body.put("modelId", version.modelId());
        body.put("sysPrompt", version.sysPrompt());
        body.put("workspace", version.workspace());
        body.put("releaseVersion", release);
        return body;
    }

    @ExceptionHandler(CatalogException.class)
    public ResponseEntity<ApiError> handleCatalog(CatalogException e) {
        HttpStatus status = switch (e.code()) {
            case "AGENT_NOT_FOUND", "VERSION_NOT_FOUND" -> HttpStatus.NOT_FOUND;
            case "AGENT_EXISTS", "VERSION_EXISTS", "VERSION_DRAINING", "AGENT_DISABLED" -> HttpStatus.CONFLICT;
            default -> HttpStatus.BAD_REQUEST;
        };
        return ResponseEntity.status(status)
                .body(ApiError.of(e.code(), e.getMessage(), requestId()));
    }

    private static String requestId() {
        ServletRequestAttributes attrs =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attrs != null && attrs.getRequest().getHeader("X-Request-Id") != null) {
            return attrs.getRequest().getHeader("X-Request-Id");
        }
        return UUID.randomUUID().toString();
    }

    public record UpdateAgentRequest(String name, Boolean enabled) {
    }

    public record CreateAgentRequest(
            @NotBlank String agentId,
            String name,
            String systemCode
    ) {
    }

    public record CreateVersionRequest(
            Integer version,
            @NotBlank String modelId,
            String sysPrompt,
            String workspace
    ) {
    }

    }
