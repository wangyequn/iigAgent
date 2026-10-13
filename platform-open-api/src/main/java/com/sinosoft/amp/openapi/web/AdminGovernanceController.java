package com.sinosoft.amp.openapi.web;

import com.sinosoft.amp.common.api.ApiError;
import com.sinosoft.amp.openapi.catalog.CatalogException;
import com.sinosoft.amp.openapi.governance.ApprovalItem;
import com.sinosoft.amp.openapi.governance.GovernanceService;
import com.sinosoft.amp.openapi.governance.McpServerRecord;
import com.sinosoft.amp.openapi.governance.PermissionRuleRecord;
import com.sinosoft.amp.openapi.security.AuthContext;
import com.sinosoft.amp.openapi.tool.BusinessToolRecord;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
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
 * S4 治理 API：权限规则 / Mode / MCP / 业务工具 / 审批收件箱。
 */
@RestController
@RequestMapping("/api/v1/admin")
public class AdminGovernanceController {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(AdminGovernanceController.class);

    private final GovernanceService governance;
    private final com.sinosoft.amp.openapi.agent.AgentInstanceCache instanceCache;
    private final com.sinosoft.amp.openapi.session.SessionStateRuleScrubber ruleScrubber;

    public AdminGovernanceController(
            GovernanceService governance,
            com.sinosoft.amp.openapi.agent.AgentInstanceCache instanceCache,
            com.sinosoft.amp.openapi.session.SessionStateRuleScrubber ruleScrubber) {
        this.governance = governance;
        this.instanceCache = instanceCache;
        this.ruleScrubber = ruleScrubber;
    }

    /**
     * 权限规则/Mode 只在 build 时注入实例，改完必须驱逐缓存，
     * 否则控制台改动对已缓存实例无效（要等发布新版本才生效）。
     * 同时重同步 Redis 会话状态的 permission_context——状态里既有的 ask/allow/mode
     * 不会被重建覆盖（引擎优先读状态），不清洗则旧会话继续按旧规则判定。
     */
    private void evictInstances(String tenantId, String reason) {
        try {
            var evicted = instanceCache.evictTenant(tenantId);
            log.info("governance change evicted instances tenant={} reason={} keys={}",
                    tenantId, reason, evicted);
        } catch (RuntimeException e) {
            log.warn("evict after governance change failed tenant={}: {}", tenantId, e.toString());
        }
    }

    @GetMapping("/governance/permissions")
    public Map<String, Object> getPermissions(
            @RequestParam(required = false) String agentId) {
        String tenant = AuthContext.requireTenantId();
        agentId = AuthContext.requireAgentIdOrDefault(agentId);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("tenantId", tenant);
        body.put("agentId", agentId);
        body.put("mode", governance.getMode(tenant, agentId));
        List<Map<String, Object>> rules = new ArrayList<>();
        for (PermissionRuleRecord r : governance.listRules(tenant, agentId)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("toolName", r.toolName());
            row.put("behavior", r.behavior());
            row.put("ruleContent", r.ruleContent());
            row.put("source", r.source());
            rules.add(row);
        }
        body.put("rules", rules);
        return body;
    }

    @PostMapping("/governance/permissions")
    public Map<String, Object> upsertPermission(@Valid @RequestBody PermissionRequest request) {
        String tenant = AuthContext.requireTenantId();
        String agentId = AuthContext.requireAgentIdOrDefault(request.agentId());
        PermissionRuleRecord rec = governance.upsertRule(
                tenant, agentId, request.toolName(), request.behavior(), request.ruleContent(), request.source());
        evictInstances(tenant, "permission-rule:" + request.toolName() + ":" + request.behavior());
        ruleScrubber.resyncTool(request.toolName(), request.behavior());
        return Map.of(
                "tenantId", rec.tenantId(),
                "agentId", rec.agentId(),
                "toolName", rec.toolName(),
                "behavior", rec.behavior(),
                "ruleContent", rec.ruleContent());
    }

    @DeleteMapping("/governance/permissions")
    public Map<String, Object> deletePermission(@RequestBody PermissionDeleteRequest request) {
        String tenant = AuthContext.requireTenantId();
        String agentId = AuthContext.requireAgentIdOrDefault(request.agentId());
        governance.deleteRule(tenant, agentId, request.toolName(), request.behavior(), request.ruleContent());
        evictInstances(tenant, "permission-delete:" + request.toolName());
        ruleScrubber.resyncTool(request.toolName(), request.behavior() == null ? "DENY" : request.behavior());
        return Map.of("deleted", true, "toolName", request.toolName(), "agentId", agentId);
    }

    @PostMapping("/governance/permission-mode")
    public Map<String, Object> setMode(@Valid @RequestBody ModeRequest request) {
        String tenant = AuthContext.requireTenantId();
        String agentId = AuthContext.requireAgentIdOrDefault(request.agentId());
        governance.setMode(tenant, agentId, request.mode());
        evictInstances(tenant, "permission-mode:" + request.mode());
        ruleScrubber.resyncMode(request.mode());
        return Map.of("tenantId", tenant, "agentId", agentId, "mode", governance.getMode(tenant, agentId));
    }

    @GetMapping("/mcp-servers")
    public Map<String, Object> listMcp() {
        String tenant = AuthContext.requireTenantId();
        List<Map<String, Object>> list = new ArrayList<>();
        for (McpServerRecord m : governance.listMcp(tenant)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", m.name());
            row.put("transport", m.transport());
            row.put("command", m.command());
            row.put("args", m.args());
            row.put("url", m.url());
            row.put("enableTools", m.enableTools());
            row.put("status", m.status());
            list.add(row);
        }
        return Map.of("tenantId", tenant, "servers", list);
    }

    @PostMapping("/mcp-servers")
    public Map<String, Object> registerMcp(@Valid @RequestBody McpRequest request) {
        String tenant = AuthContext.requireTenantId();
        McpServerRecord rec = governance.registerMcp(
                tenant, request.name(), request.transport(), request.command(), request.args(),
                request.url(), request.enableTools(), request.headers());
        return Map.of(
                "name", rec.name(),
                "transport", rec.transport(),
                "url", rec.url() == null ? "" : rec.url(),
                "status", rec.status());
    }

    /** 批量工具授权：{"items":[{agentId, toolName, behavior|null=清除}]} */
    @PostMapping("/governance/permissions/batch")
    public Map<String, Object> upsertPermissionsBatch(@RequestBody BatchPermissionRequest request) {
        String tenant = AuthContext.requireTenantId();
        List<PermissionRequest> items = request == null || request.items() == null
                ? List.of() : request.items();
        int applied = 0;
        for (PermissionRequest item : items) {
            if (item == null || item.toolName() == null || item.toolName().isBlank()) {
                continue;
            }
            String agentId = AuthContext.requireAgentIdOrDefault(item.agentId());
            if (item.behavior() == null || item.behavior().isBlank()) {
                for (String b : new String[]{"ALLOW", "ASK", "DENY"}) {
                    governance.deleteRule(tenant, agentId, item.toolName(), b, item.ruleContent());
                }
            } else {
                governance.upsertRule(tenant, agentId, item.toolName(), item.behavior(),
                        item.ruleContent(), item.source() == null ? "console" : item.source());
            }
            applied++;
        }
        if (applied > 0) {
            evictInstances(tenant, "permission-batch:" + applied);
            try {
                ruleScrubber.resyncTool("*", "ALLOW");
            } catch (RuntimeException ignored) {
                // 批量无单一 tool 语义，尽力重同步
            }
        }
        return Map.of("applied", applied);
    }

    @GetMapping("/business-tools")
    public Map<String, Object> listTools() {
        String tenant = AuthContext.requireTenantId();
        List<Map<String, Object>> list = new ArrayList<>();
        for (BusinessToolRecord t : governance.listTools(tenant)) {
            list.add(toolRow(t));
        }
        return Map.of("tenantId", tenant, "tools", list);
    }

    @GetMapping("/business-tools/{toolName}")
    public Map<String, Object> getTool(@PathVariable String toolName) {
        String tenant = AuthContext.requireTenantId();
        return toolRow(governance.findTool(tenant, toolName));
    }

    @PostMapping("/business-tools")
    public Map<String, Object> registerTool(@Valid @RequestBody ToolRequest request) {
        String tenant = AuthContext.requireTenantId();
        BusinessToolRecord rec = governance.registerTool(
                tenant, request.toolName(), request.description(), request.kind(), request.config(),
                new com.sinosoft.amp.openapi.governance.GovernanceService.ToolUpsertOptions(
                        request.lifecycle(), request.env(), request.owner(), request.secretRef(),
                        request.timeoutMs(), request.maxRetries(), request.rateLimitQps(),
                        request.tags(), request.updatedBy(), request.systemCode()));
        return toolRow(rec);
    }

    @PatchMapping("/business-tools/{toolName}")
    public Map<String, Object> patchTool(@PathVariable String toolName, @RequestBody ToolRequest request) {
        String tenant = AuthContext.requireTenantId();
        BusinessToolRecord existing = governance.findTool(tenant, toolName);
        BusinessToolRecord rec = governance.registerTool(
                tenant, toolName,
                request.description() == null ? existing.description() : request.description(),
                request.kind() == null ? existing.kind() : request.kind(),
                request.config() == null ? existing.config() : request.config(),
                new com.sinosoft.amp.openapi.governance.GovernanceService.ToolUpsertOptions(
                        request.lifecycle() == null ? existing.status() : request.lifecycle(),
                        request.env() == null ? existing.env() : request.env(),
                        request.owner() == null ? existing.owner() : request.owner(),
                        request.secretRef() == null ? existing.secretRef() : request.secretRef(),
                        request.timeoutMs() == null ? existing.timeoutMs() : request.timeoutMs(),
                        request.maxRetries() == null ? existing.maxRetries() : request.maxRetries(),
                        request.rateLimitQps() == null ? existing.rateLimitQps() : request.rateLimitQps(),
                        request.tags() == null ? existing.tags() : request.tags(),
                        request.updatedBy(),
                        request.systemCode() == null ? existing.systemCode() : request.systemCode()));
        return toolRow(rec);
    }

    @PostMapping("/business-tools/{toolName}/lifecycle")
    public Map<String, Object> toolLifecycle(
            @PathVariable String toolName,
            @RequestBody(required = false) ToolLifecycleRequest request) {
        String tenant = AuthContext.requireTenantId();
        String lifecycle = request == null ? null : request.lifecycle();
        String reason = request == null ? null : request.reason();
        String by = request == null || request.updatedBy() == null ? "admin" : request.updatedBy();
        if ("DEPRECATED".equalsIgnoreCase(lifecycle)) {
            return toolRow(governance.setToolLifecycle(tenant, toolName, "DEPRECATED", reason, by));
        }
        return toolRow(governance.setToolLifecycle(tenant, toolName, lifecycle, reason, by));
    }

    @PostMapping("/business-tools/{toolName}/test")
    public Map<String, Object> testTool(@PathVariable String toolName) {
        String tenant = AuthContext.requireTenantId();
        BusinessToolRecord t = governance.findTool(tenant, toolName);
        long t0 = System.currentTimeMillis();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("toolName", t.toolName());
        out.put("kind", t.kind());
        try {
            if (!"HTTP".equalsIgnoreCase(t.kind())) {
                out.put("ok", true);
                out.put("note", "non-HTTP tool: registry only; connectivity not applicable");
                out.put("durationMs", System.currentTimeMillis() - t0);
                return out;
            }
            com.sinosoft.amp.openapi.tool.HttpToolSpec spec =
                    com.sinosoft.amp.openapi.tool.HttpToolSpec.from(t);
            java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
                    .connectTimeout(java.time.Duration.ofSeconds(5))
                    .build();
            java.net.http.HttpRequest.Builder rb = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create(spec.url()))
                    .timeout(java.time.Duration.ofMillis(t.timeoutMsOr(10_000)));
            for (var e : spec.headers().entrySet()) {
                String v = e.getValue() == null ? "" : e.getValue();
                if (v.startsWith("secret:") && t.secretRef() != null) {
                    v = governance.resolveToolSecret(tenant, t.secretRef()).orElse("");
                }
                rb.header(e.getKey(), v);
            }
            if ("GET".equalsIgnoreCase(spec.method()) || "HEAD".equalsIgnoreCase(spec.method())) {
                rb.method(spec.method(), java.net.http.HttpRequest.BodyPublishers.noBody());
            } else {
                // 试调用默认只做连通：写方法发空 JSON，避免误写业务
                rb.header("Content-Type", "application/json");
                rb.method(spec.method(), java.net.http.HttpRequest.BodyPublishers.ofString("{}"));
            }
            java.net.http.HttpResponse<String> res =
                    client.send(rb.build(), java.net.http.HttpResponse.BodyHandlers.ofString());
            long ms = System.currentTimeMillis() - t0;
            String body = res.body() == null ? "" : res.body();
            out.put("ok", res.statusCode() >= 200 && res.statusCode() < 400);
            out.put("httpStatus", res.statusCode());
            out.put("durationMs", ms);
            out.put("bodyPreview", body.length() > 500 ? body.substring(0, 500) + "..." : body);
        } catch (Exception e) {
            out.put("ok", false);
            out.put("error", e.toString());
            out.put("durationMs", System.currentTimeMillis() - t0);
        }
        return out;
    }

    @PostMapping("/business-tools/{toolName}/clone")
    public Map<String, Object> cloneTool(@PathVariable String toolName, @RequestBody CloneToolRequest request) {
        String tenant = AuthContext.requireTenantId();
        BusinessToolRecord src = governance.findTool(tenant, toolName);
        String newName = request == null || request.toolName() == null || request.toolName().isBlank()
                ? toolName + "-copy"
                : request.toolName();
        BusinessToolRecord rec = governance.registerTool(
                tenant, newName, src.description(), src.kind(), src.config(),
                new com.sinosoft.amp.openapi.governance.GovernanceService.ToolUpsertOptions(
                        "DRAFT", src.env(), src.owner(), src.secretRef(),
                        src.timeoutMs(), src.maxRetries(), src.rateLimitQps(), src.tags(), "clone", src.systemCode()));
        return toolRow(rec);
    }

    @DeleteMapping("/business-tools/{toolName}")
    public Map<String, Object> deleteTool(
            @PathVariable String toolName,
            @RequestParam(required = false, defaultValue = "false") boolean hard) {
        String tenant = AuthContext.requireTenantId();
        if (hard) {
            governance.hardDeleteTool(tenant, toolName);
            return Map.of("deleted", true, "mode", "hard", "toolName", toolName);
        }
        BusinessToolRecord rec = governance.setToolLifecycle(tenant, toolName, "DEPRECATED", "deleted via api", "admin");
        return Map.of("deleted", true, "mode", "deprecated", "toolName", toolName, "status", rec.status());
    }

    @GetMapping("/agents/{agentId}/tool-bindings")
    public Map<String, Object> listToolBindings(@PathVariable String agentId) {
        String tenant = AuthContext.requireTenantId();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (var b : governance.listToolBindings(tenant, agentId)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("toolName", b.toolName());
            m.put("enabled", b.enabled());
            m.put("policyOverride", b.policyOverride());
            rows.add(m);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("tenantId", tenant);
        body.put("agentId", agentId);
        body.put("bindings", rows);
        body.put("mountableTools", governance.mountableTools(tenant, agentId).stream()
                .map(BusinessToolRecord::toolName).toList());
        return body;
    }

    @PostMapping("/agents/{agentId}/tool-bindings")
    public Map<String, Object> upsertToolBinding(
            @PathVariable String agentId,
            @RequestBody ToolBindingRequest request) {
        String tenant = AuthContext.requireTenantId();
        governance.upsertToolBinding(new com.sinosoft.amp.openapi.governance.AgentToolBindingRecord(
                tenant, agentId, request.toolName(), Boolean.TRUE.equals(request.enabled()), request.policyOverride()));
        evictInstances(tenant, "tool-binding:" + request.toolName());
        return Map.of("agentId", agentId, "toolName", request.toolName(), "enabled", Boolean.TRUE.equals(request.enabled()));
    }

    @DeleteMapping("/agents/{agentId}/tool-bindings/{toolName}")
    public Map<String, Object> deleteToolBinding(@PathVariable String agentId, @PathVariable String toolName) {
        String tenant = AuthContext.requireTenantId();
        governance.deleteToolBinding(tenant, agentId, toolName);
        evictInstances(tenant, "tool-binding-del:" + toolName);
        return Map.of("deleted", true, "agentId", agentId, "toolName", toolName);
    }

    @PutMapping("/tool-credentials/{refName}")
    public Map<String, Object> putSecret(@PathVariable String refName, @RequestBody ToolSecretRequest request) {
        String tenant = AuthContext.requireTenantId();
        governance.putToolSecret(tenant, refName, request.kind(), request.secretValue());
        return Map.of("refName", refName, "stored", true);
    }

    @DeleteMapping("/tool-credentials/{refName}")
    public Map<String, Object> deleteSecret(@PathVariable String refName) {
        String tenant = AuthContext.requireTenantId();
        governance.deleteToolSecret(tenant, refName);
        return Map.of("deleted", true, "refName", refName);
    }

    private Map<String, Object> toolRow(BusinessToolRecord t) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("toolName", t.toolName());
        row.put("displayName", t.description() == null || t.description().isBlank() ? t.toolName() : t.description());
        row.put("description", t.description());
        row.put("kind", t.kind());
        row.put("status", t.status());
        row.put("lifecycle", t.status());
        row.put("config", t.config() == null ? "" : t.config());
        row.put("version", t.version());
        row.put("env", t.env());
        row.put("owner", t.owner());
        row.put("secretRef", t.secretRef());
        row.put("timeoutMs", t.timeoutMs());
        row.put("maxRetries", t.maxRetries());
        row.put("rateLimitQps", t.rateLimitQps());
        row.put("tags", t.tags());
        row.put("updatedBy", t.updatedBy());
        row.put("updatedAt", t.updatedAt());
        row.put("deprecatedReason", t.deprecatedReason());
        row.put("systemCode", t.systemCode());
        try {
            com.sinosoft.amp.openapi.tool.ToolMeta meta =
                    com.sinosoft.amp.openapi.tool.ToolMeta.fromConfig(t.config(), null);
            row.put("risk", meta.risk());
            row.put("permCode", meta.permCode());
            row.put("authMode", meta.authMode());
            row.put("ownerSystem", meta.ownerSystem());
            row.put("readOnly", meta.readOnly());
        } catch (RuntimeException e) {
            row.put("risk", "medium");
            row.put("authMode", "user_token");
        }
        // 供前端表格/详情直接展示 HTTP 连接面
        try {
            if ("HTTP".equalsIgnoreCase(t.kind()) && t.config() != null) {
                com.fasterxml.jackson.databind.JsonNode node =
                        new com.fasterxml.jackson.databind.ObjectMapper().readTree(t.config());
                row.put("url", node.path("url").asText(""));
                row.put("method", node.path("method").asText("GET"));
            }
        } catch (Exception ignored) {
            // keep row without url
        }
        return row;
    }

    @GetMapping("/inbox")
    public Map<String, Object> inbox(
            @RequestParam(required = false) String agentId,
            @RequestParam(required = false) String sessionId) {
        String tenant = AuthContext.requireTenantId();
        String agent = AuthContext.requireAgentIdOrDefault(agentId);
        String sid = sessionId == null || sessionId.isBlank() ? "" : sessionId;
        List<Map<String, Object>> items = new ArrayList<>();
        for (ApprovalItem a : governance.pending(tenant, agent, sid)) {
            items.add(approvalBody(a));
        }
        return Map.of("tenantId", tenant, "agentId", agent, "items", items);
    }

    @PostMapping("/approvals/{id}/decide")
    public Map<String, Object> decide(
            @PathVariable String id,
            @Valid @RequestBody DecideRequest request) {
        String tenant = AuthContext.requireTenantId();
        ApprovalItem item = governance.findApproval(id)
                .orElseThrow(() -> new CatalogException("APPROVAL_NOT_FOUND", "approval not found"));
        if (!tenant.equals(item.tenantId())) {
            throw new CatalogException("FORBIDDEN", "approval tenant mismatch");
        }
        ApprovalItem decided = governance.decide(id, Boolean.TRUE.equals(request.confirmed()), request.note(), request.idempotencyKey());
        return approvalBody(decided);
    }

    private Map<String, Object> approvalBody(ApprovalItem a) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", a.id());
        row.put("agentId", a.agentId());
        row.put("userId", a.userId());
        row.put("sessionId", a.sessionId());
        row.put("replyId", a.replyId());
        row.put("toolCallId", a.toolCallId());
        row.put("toolName", a.toolName());
        row.put("toolInput", a.toolInput());
        row.put("status", a.status());
        row.put("decisionNote", a.decisionNote());
        row.put("createdAt", a.createdAt());
        row.put("resolvedAt", a.resolvedAt());
        return row;
    }

    private String requireDemoAgent() {
        // 默认 demo；管理台可带 agentId 的接口已单独处理
        return "demo";
    }

    @ExceptionHandler(CatalogException.class)
    public ResponseEntity<ApiError> handleCatalog(CatalogException e) {
        HttpStatus status = switch (e.code()) {
            case "APPROVAL_NOT_FOUND", "AGENT_NOT_FOUND", "TOOL_NOT_FOUND", "MCP_NOT_FOUND" -> HttpStatus.NOT_FOUND;
            case "APPROVAL_ALREADY_RESOLVED" -> HttpStatus.CONFLICT;
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

    public record PermissionRequest(String agentId, @NotBlank String toolName, String behavior, String ruleContent, String source) {
    }

    public record BatchPermissionRequest(List<PermissionRequest> items) {
    }

    public record PermissionDeleteRequest(String agentId, String toolName, String behavior, String ruleContent) {
    }

    public record ModeRequest(String agentId, @NotBlank String mode) {
    }

    public record McpRequest(
            @NotBlank String name,
            String transport,
            String command,
            String args,
            String url,
            String enableTools,
            Map<String, String> headers
    ) {
    }

    public record ToolRequest(
            @NotBlank String toolName,
            String description,
            String kind,
            String config,
            String lifecycle,
            String env,
            String owner,
            String secretRef,
            Integer timeoutMs,
            Integer maxRetries,
            Integer rateLimitQps,
            String tags,
            String updatedBy,
            String systemCode) {
    }

    public record ToolLifecycleRequest(String lifecycle, String reason, String updatedBy) {
    }

    public record CloneToolRequest(String toolName) {
    }

    public record ToolBindingRequest(String toolName, Boolean enabled, String policyOverride) {
    }

    public record ToolSecretRequest(String kind, String secretValue) {
    }

    public record DecideRequest(Boolean confirmed, String note, String idempotencyKey) {
    }
}
