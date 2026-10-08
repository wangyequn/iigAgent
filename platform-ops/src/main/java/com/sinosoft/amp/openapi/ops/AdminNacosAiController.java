package com.sinosoft.amp.openapi.ops;

import com.sinosoft.amp.openapi.security.AuthContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Nacos AI Registry 管理面（admin scope）：只读发现 + 单向导入。
 */
@RestController
@RequestMapping("/api/v1/admin/nacos")
public class AdminNacosAiController {

    private final NacosAiSyncService sync;
    private final NacosAiAdminClient client;
    private final NacosAiProperties properties;
    private final NacosPromptService promptService;

    public AdminNacosAiController(
            NacosAiSyncService sync, NacosAiAdminClient client, NacosAiProperties properties,
            NacosPromptService promptService) {
        this.sync = sync;
        this.client = client;
        this.properties = properties;
        this.promptService = promptService;
    }

    @GetMapping("/ai")
    public Map<String, Object> aiStatus() {
        AuthContext.requireTenantId();
        Map<String, Object> m = sync.status();
        m.put("prompt", promptService.status());
        return m;
    }

    /** Prompt 热更：清缓存；下一轮 create 实例时拉新模板。 */
    @PostMapping("/prompts/refresh")
    public Map<String, Object> refreshPrompts(@RequestParam(required = false) String agentId) {
        AuthContext.requireTenantId();
        promptService.invalidate(agentId);
        return Map.of("ok", true, "agentId", agentId == null ? "*" : agentId, "prompt", promptService.status());
    }

    @GetMapping("/prompts")
    public Map<String, Object> listPrompts(
            @RequestParam(required = false) String tenantId,
            @RequestParam(required = false) String promptKey) {
        String tenant = resolveTenant(tenantId);
        String ns = properties.effectiveNamespace(tenant);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("namespace", ns);
        out.put("prompt", promptService.status());
        if (promptKey != null && !promptKey.isBlank()) {
            var view = promptService.getDetail(ns, promptKey, null, null);
            out.put("detail", view.orElse(null));
        }
        return out;
    }

    @GetMapping("/mcp")
    public Map<String, Object> listMcp(@RequestParam(required = false) String tenantId) {
        String tenant = resolveTenant(tenantId);
        String ns = properties.effectiveNamespace(tenant);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("namespace", ns);
        try {
            out.put("items", client.listMcp(ns));
        } catch (Exception e) {
            out.put("error", e.toString());
        }
        return out;
    }

    @PostMapping("/mcp/sync")
    public Map<String, Object> syncMcp(@RequestParam(required = false) String tenantId) {
        AuthContext.requireTenantId();
        return sync.syncMcp(resolveTenant(tenantId));
    }

    @GetMapping("/agents")
    public Map<String, Object> listAgents(
            @RequestParam(required = false) String tenantId,
            @RequestParam(required = false) String namePrefix) {
        String tenant = resolveTenant(tenantId);
        String ns = properties.effectiveNamespace(tenant);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("namespace", ns);
        out.put("agentCardMode", properties.agentCardMode());
        try {
            out.put("items", client.listAgents(ns, namePrefix));
        } catch (Exception e) {
            out.put("error", e.toString());
        }
        return out;
    }

    @PostMapping("/agents/import")
    public Map<String, Object> importAgents(
            @RequestParam(required = false) String tenantId,
            @RequestParam(required = false) String namePrefix) {
        AuthContext.requireTenantId();
        return sync.importAgents(resolveTenant(tenantId), namePrefix);
    }

    @GetMapping("/skills")
    public Map<String, Object> listSkills(
            @RequestParam(required = false) String tenantId,
            @RequestParam(required = false) String namePrefix) {
        String tenant = resolveTenant(tenantId);
        String ns = properties.effectiveNamespace(tenant);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("namespace", ns);
        try {
            out.put("items", client.listSkills(ns, namePrefix));
        } catch (Exception e) {
            out.put("error", e.toString());
            out.put("note", "Skill API may require Nacos >=3.2");
        }
        return out;
    }

    @PostMapping("/skills/import")
    public Map<String, Object> importSkills(
            @RequestParam(required = false) String tenantId,
            @RequestParam(required = false) String namePrefix) {
        AuthContext.requireTenantId();
        return sync.importSkills(resolveTenant(tenantId), namePrefix);
    }

    private String resolveTenant(String tenantId) {
        String t = AuthContext.requireTenantId();
        return (tenantId == null || tenantId.isBlank()) ? t : tenantId;
    }
}
