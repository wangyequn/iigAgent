package com.sinosoft.amp.openapi.web;

import com.sinosoft.amp.openapi.agent.CatalogRuntimeService;
import com.sinosoft.amp.openapi.catalog.AgentRecord;
import com.sinosoft.amp.openapi.catalog.AgentStatus;
import com.sinosoft.amp.openapi.catalog.AgentVersionRecord;
import com.sinosoft.amp.openapi.security.AuthContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 管理台：按当前 API Key 租户读目录，含 release 版本指针。
 */
@RestController
@RequestMapping("/api/v1")
public class AgentAdminController {

    private final CatalogRuntimeService catalogRuntime;

    public AgentAdminController(CatalogRuntimeService catalogRuntime) {
        this.catalogRuntime = catalogRuntime;
    }

    @GetMapping("/agents")
    public Map<String, Object> listAgents() {
        String tenant = AuthContext.requireTenantId();
        List<Map<String, Object>> agents = new ArrayList<>();
        for (AgentRecord record : catalogRuntime.listAgents(tenant)) {
            Map<String, Object> agent = new LinkedHashMap<>();
            agent.put("id", record.agentId());
            agent.put("name", record.name());
            agent.put("enabled", record.status() == AgentStatus.ACTIVE);
            Optional<Integer> release = catalogRuntime.releaseVersion(tenant, record.agentId());
            release.ifPresent(v -> agent.put("releaseVersion", v));
            release.flatMap(rv -> catalogRuntime.listVersions(tenant, record.agentId()).stream()
                            .filter(v -> v.version() == rv)
                            .findFirst())
                    .ifPresent(v -> agent.put("modelId", v.modelId()));
            agent.put("tenantId", record.tenantId());
            agents.add(agent);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("agents", agents);
        body.put("tenantId", tenant);
        return body;
    }

    @GetMapping("/agents/{agentId}/versions")
    public Map<String, Object> listVersions(@PathVariable String agentId) {
        String tenant = AuthContext.requireTenantId();
        AuthContext.requireAgent(agentId);
        List<Map<String, Object>> versions = new ArrayList<>();
        for (AgentVersionRecord v : catalogRuntime.listVersions(tenant, agentId)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("version", v.version());
            row.put("status", v.status().name());
            row.put("modelId", v.modelId());
            row.put("sysPrompt", v.sysPrompt());
            row.put("workspace", v.workspace());
            row.put("publishedAt", v.publishedAt());
            versions.add(row);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("agentId", agentId);
        body.put("tenantId", tenant);
        body.put("releaseVersion", catalogRuntime.releaseVersion(tenant, agentId).orElse(null));
        body.put("versions", versions);
        return body;
    }
}
