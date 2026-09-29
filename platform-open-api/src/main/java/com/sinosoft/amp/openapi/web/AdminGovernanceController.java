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
    public Map<String, Object> getPermissions() {
        String tenant = AuthContext.requireTenantId();
        String agentId = requireDemoAgent();
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
                request.url(), request.enableTools());
        return Map.of(
                "name", rec.name(),
                "transport", rec.transport(),
                "url", rec.url() == null ? "" : rec.url(),
                "status", rec.status());
    }

    @GetMapping("/business-tools")
    public Map<String, Object> listTools() {
        String tenant = AuthContext.requireTenantId();
        List<Map<String, Object>> list = new ArrayList<>();
        for (BusinessToolRecord t : governance.listTools(tenant)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("toolName", t.toolName());
            row.put("description", t.description());
            row.put("kind", t.kind());
            row.put("status", t.status());
            list.add(row);
        }
        return Map.of("tenantId", tenant, "tools", list);
    }

    @PostMapping("/business-tools")
    public Map<String, Object> registerTool(@Valid @RequestBody ToolRequest request) {
        String tenant = AuthContext.requireTenantId();
        BusinessToolRecord rec = governance.registerTool(
                tenant, request.toolName(), request.description(), request.kind(), request.config());
        return Map.of(
                "toolName", rec.toolName(),
                "kind", rec.kind(),
                "status", rec.status(),
                "description", rec.description() == null ? "" : rec.description());
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
            case "APPROVAL_NOT_FOUND", "AGENT_NOT_FOUND" -> HttpStatus.NOT_FOUND;
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

    public record PermissionRequest(String agentId, @NotBlank String toolName, @NotBlank String behavior, String ruleContent, String source) {
    }

    public record ModeRequest(String agentId, @NotBlank String mode) {
    }

    public record McpRequest(
            @NotBlank String name,
            String transport,
            String command,
            String args,
            String url,
            String enableTools
    ) {
    }

    public record ToolRequest(@NotBlank String toolName, String description, String kind, String config) {
    }

    public record DecideRequest(Boolean confirmed, String note, String idempotencyKey) {
    }
}
