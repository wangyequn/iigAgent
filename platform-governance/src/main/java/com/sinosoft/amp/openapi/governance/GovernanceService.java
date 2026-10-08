package com.sinosoft.amp.openapi.governance;

import com.sinosoft.amp.openapi.catalog.CatalogException;
import com.sinosoft.amp.openapi.tool.BusinessToolRecord;
import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionMode;
import io.agentscope.core.permission.PermissionRule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * S4 治理服务：权限规则 / Mode / MCP 登记 / 业务工具目录 / 审批。
 */
@Service
public class GovernanceService {

    private static final Logger log = LoggerFactory.getLogger(GovernanceService.class);

    private final GovernanceRepository repo;

    public GovernanceService(GovernanceRepository repo) {
        this.repo = repo;
    }

    public PermissionContextState buildPermissionContext(String tenantId, String agentId) {
        PermissionMode mode = PermissionMode.fromString(repo.findPermissionMode(tenantId, agentId));
        PermissionContextState.Builder b = PermissionContextState.builder().mode(mode);
        for (PermissionRuleRecord r : repo.listPermissionRules(tenantId, agentId)) {
            PermissionBehavior behavior = switch (r.behavior().toUpperCase(Locale.ROOT)) {
                case "ALLOW" -> PermissionBehavior.ALLOW;
                case "DENY" -> PermissionBehavior.DENY;
                case "PASSTHROUGH" -> PermissionBehavior.PASSTHROUGH;
                default -> PermissionBehavior.ASK;
            };
            PermissionRule rule = new PermissionRule(
                    r.toolName(),
                    normalizeRuleContent(r.ruleContent()),
                    behavior,
                    r.source() == null ? "platform" : r.source());
            if (behavior == PermissionBehavior.ALLOW) {
                b.addAllowRule(r.toolName(), rule);
            } else if (behavior == PermissionBehavior.DENY) {
                b.addDenyRule(r.toolName(), rule);
            } else if (behavior == PermissionBehavior.ASK) {
                b.addAskRule(r.toolName(), rule);
            }
        }
        return b.build();
    }

    public List<PermissionRuleRecord> listRules(String tenantId, String agentId) {
        return repo.listPermissionRules(tenantId, agentId);
    }

    /**
     * AgentScope 的 ToolBase.matchRule 仅当 ruleContent 为 null 时才匹配非文件类工具；
     * 库里沿用的通配符 "*" 必须映射为 null，否则 ALLOW/ASK/DENY 规则全部无法命中，
     * 工具会退化为「一律按 mode 默认处理」（DEFAULT=每次询问，DONT_ASK=一律拒绝）。
     */
    static String normalizeRuleContent(String stored) {
        if (stored == null || stored.isBlank() || "*".equals(stored.trim())) {
            return null;
        }
        return stored;
    }

    public PermissionRuleRecord upsertRule(String tenantId, String agentId, String toolName, String behavior, String ruleContent, String source) {
        String b = behavior == null ? "" : behavior.toUpperCase(Locale.ROOT);
        if (!PermissionRuleRecord.ALLOW.equals(b)
                && !PermissionRuleRecord.ASK.equals(b)
                && !PermissionRuleRecord.DENY.equals(b)) {
            throw new CatalogException("PERMISSION_BEHAVIOR_INVALID", "behavior must be ALLOW/ASK/DENY");
        }
        if (toolName == null || toolName.isBlank()) {
            throw new CatalogException("TOOL_NAME_REQUIRED", "toolName is required");
        }
        PermissionRuleRecord rec = new PermissionRuleRecord(
                tenantId, agentId, toolName,
                ruleContent == null || ruleContent.isBlank() ? "*" : ruleContent,
                b, source == null ? "platform" : source);
        repo.upsertPermissionRule(rec);
        // 同一工具 + 同一匹配内容只保留一种行为：规则表主键含 behavior，ALLOW/ASK 可并存，
        // 而引擎按 DENY→ASK→ALLOW 判定，并存的 ASK 会盖掉 ALLOW（表现为「已放行却仍要确认」）。
        for (String other : new String[]{PermissionRuleRecord.ALLOW, PermissionRuleRecord.ASK, PermissionRuleRecord.DENY}) {
            if (!other.equals(b)) {
                repo.deletePermissionRule(tenantId, agentId, toolName, other, rec.ruleContent());
            }
        }
        log.info("upsert permission rule {} {} {} {}", tenantId, agentId, toolName, b);
        return rec;
    }

    public void deleteRule(String tenantId, String agentId, String toolName, String behavior, String ruleContent) {
        repo.deletePermissionRule(tenantId, agentId, toolName, behavior, ruleContent == null ? "*" : ruleContent);
        log.info("delete permission rule {} {} {} {}", tenantId, agentId, toolName, behavior);
    }

    public boolean deleteTool(String tenantId, String toolName) {
        try {
            repo.deleteBusinessTool(tenantId, toolName);
            return true;
        } catch (RuntimeException e) {
            log.warn("delete business tool failed {}: {}", toolName, e.toString());
            return false;
        }
    }

    public void setMode(String tenantId, String agentId, String mode) {
        try {
            PermissionMode.fromString(mode);
        } catch (RuntimeException e) {
            throw new CatalogException("PERMISSION_MODE_INVALID", "invalid mode: " + mode);
        }
        repo.upsertPermissionMode(tenantId, agentId, mode);
    }

    public String getMode(String tenantId, String agentId) {
        return repo.findPermissionMode(tenantId, agentId);
    }

    public List<McpServerRecord> listMcp(String tenantId) {
        return repo.listMcpServers(tenantId);
    }

    public McpServerRecord registerMcp(String tenantId, String name, String transport, String command, String args, String url, String enableTools) {
        if (name == null || name.isBlank()) {
            throw new CatalogException("MCP_NAME_REQUIRED", "name is required");
        }
        String t = transport == null ? "streamable_http" : transport.toLowerCase(Locale.ROOT);
        if (!t.equals("stdio") && !t.equals("sse") && !t.equals("streamable_http") && !t.equals("http")) {
            throw new CatalogException("MCP_TRANSPORT_INVALID", "transport must be stdio/sse/streamable_http");
        }
        if (t.equals("stdio") && (command == null || command.isBlank())) {
            throw new CatalogException("MCP_COMMAND_REQUIRED", "stdio transport requires command");
        }
        if ((t.equals("sse") || t.contains("http")) && (url == null || url.isBlank())) {
            throw new CatalogException("MCP_URL_REQUIRED", "http/sse transport requires url");
        }
        McpServerRecord rec = new McpServerRecord(
                tenantId, name, t, command, args, url, enableTools, McpServerRecord.STATUS_REGISTERED,
                McpServerRecord.SOURCE_LOCAL);
        repo.upsertMcpServer(rec);
        log.info("register mcp tenant={} name={} transport={}", tenantId, name, t);
        return rec;
    }

    /** Nacos AI 单向导入（source=nacos）。local 种子/手工由 repo 层保护不覆盖。 */
    public McpServerRecord importMcpFromNacos(McpServerRecord rec) {
        if (rec == null || rec.name() == null || rec.name().isBlank()) {
            throw new CatalogException("MCP_NAME_REQUIRED", "name is required");
        }
        if (rec.enableTools() == null || rec.enableTools().isBlank()) {
            throw new CatalogException("MCP_TOOLS_REQUIRED", "enableTools required for nacos import");
        }
        McpServerRecord nacosRec = new McpServerRecord(
                rec.tenantId(), rec.name(), rec.transport(), rec.command(), rec.args(), rec.url(),
                rec.enableTools(), rec.status() == null ? McpServerRecord.STATUS_REGISTERED : rec.status(),
                McpServerRecord.SOURCE_NACOS);
        repo.upsertMcpServer(nacosRec);
        log.info("import mcp from nacos tenant={} name={}", rec.tenantId(), rec.name());
        return nacosRec;
    }

    public List<BusinessToolRecord> listTools(String tenantId) {
        return repo.listBusinessTools(tenantId);
    }

    public BusinessToolRecord registerTool(String tenantId, String toolName, String description, String kind, String config) {
        if (toolName == null || toolName.isBlank()) {
            throw new CatalogException("TOOL_NAME_REQUIRED", "toolName is required");
        }
        BusinessToolRecord rec = new BusinessToolRecord(
                tenantId, toolName, description,
                kind == null ? BusinessToolRecord.KIND_IN_PROCESS : kind,
                BusinessToolRecord.STATUS_ENABLED,
                config);
        repo.upsertBusinessTool(rec);
        return rec;
    }

    // ---- approvals ----

    public ApprovalItem openApproval(ApprovalItem item) {
        if (item.idempotencyKey() != null && !item.idempotencyKey().isBlank()) {
            Optional<ApprovalItem> existing = repo.findApprovalByIdempotency(item.idempotencyKey());
            if (existing.isPresent()) {
                return existing.get();
            }
        }
        repo.insertApproval(item);
        return item;
    }

    public Optional<ApprovalItem> findApproval(String id) {
        return repo.findApproval(id);
    }

    public Optional<ApprovalItem> findApprovalByIdempotency(String key) {
        return repo.findApprovalByIdempotency(key);
    }

    public List<ApprovalItem> pending(String tenantId, String agentId, String sessionId) {
        return repo.listPendingApprovals(tenantId, agentId, sessionId);
    }

    /**
     * 幂等确认：重复提交返回 409 APPROVAL_ALREADY_RESOLVED。
     */
    public ApprovalItem decide(String approvalId, boolean confirmed, String note, String idempotencyKey) {
        Optional<ApprovalItem> byKey = repo.findApprovalByIdempotency(idempotencyKey);
        if (byKey.isPresent() && !byKey.get().status().equals(ApprovalItem.PENDING)) {
            throw new CatalogException("APPROVAL_ALREADY_RESOLVED",
                    "approval already resolved: " + byKey.get().status());
        }
        ApprovalItem item = repo.findApproval(approvalId)
                .orElseThrow(() -> new CatalogException("APPROVAL_NOT_FOUND", "approval not found: " + approvalId));
        if (!ApprovalItem.PENDING.equals(item.status())) {
            throw new CatalogException("APPROVAL_ALREADY_RESOLVED", "approval already " + item.status());
        }
        String status = confirmed ? ApprovalItem.APPROVED : ApprovalItem.REJECTED;
        repo.updateApprovalStatus(approvalId, status, note, Instant.now());
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            // 幂等键绑定到本次决议，供重复提交检测
            repo.bindIdempotency(approvalId, idempotencyKey);
        }
        return repo.findApproval(approvalId).orElse(item);
    }

    public static String newId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    public static List<String> splitTools(String enableTools) {
        if (enableTools == null || enableTools.isBlank()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String s : enableTools.split("[,\\s]+")) {
            if (!s.isBlank()) {
                out.add(s.trim());
            }
        }
        return out;
    }
}
