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
import java.util.Map;
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
        return registerMcp(tenantId, name, transport, command, args, url, enableTools, null);
    }

    public McpServerRecord registerMcp(String tenantId, String name, String transport, String command, String args, String url, String enableTools, Map<String, String> headers) {
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
        if (enableTools == null || enableTools.isBlank()) {
            throw new CatalogException("MCP_TOOLS_REQUIRED", "enableTools is required");
        }
        McpServerRecord rec = new McpServerRecord(
                tenantId, name, t, command, args, url, enableTools, McpServerRecord.STATUS_REGISTERED,
                McpServerRecord.SOURCE_LOCAL, McpServerRecord.headersToJson(headers));
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

    // ---- business systems ----

    public List<BusinessSystemRecord> listBusinessSystems(String tenantId) {
        return repo.listBusinessSystems(tenantId);
    }

    public BusinessSystemRecord findBusinessSystem(String tenantId, String systemCode) {
        return repo.findBusinessSystem(tenantId, systemCode)
                .orElseThrow(() -> new CatalogException("SYSTEM_NOT_FOUND", "system not found: " + systemCode));
    }

    public BusinessSystemRecord upsertBusinessSystem(
            String tenantId, String systemCode, String name, String owner, String contact, String description, String status) {
        if (systemCode == null || !systemCode.matches("[A-Za-z0-9_-]{1,64}")) {
            throw new CatalogException("SYSTEM_CODE_INVALID", "systemCode must match [A-Za-z0-9_-]{1,64}");
        }
        if (name == null || name.isBlank()) {
            throw new CatalogException("SYSTEM_NAME_REQUIRED", "system name is required");
        }
        String st = status == null || status.isBlank() ? BusinessSystemRecord.STATUS_ACTIVE : status.trim().toUpperCase(Locale.ROOT);
        if (!BusinessSystemRecord.STATUS_ACTIVE.equals(st) && !BusinessSystemRecord.STATUS_DISABLED.equals(st)) {
            throw new CatalogException("SYSTEM_STATUS_INVALID", "status must be ACTIVE|DISABLED");
        }
        BusinessSystemRecord rec = new BusinessSystemRecord(
                tenantId, systemCode, name.trim(), owner, contact, description, st);
        repo.upsertBusinessSystem(rec);
        log.info("upsert business system tenant={} code={} name={}", tenantId, systemCode, name);
        return rec;
    }

    public void deleteBusinessSystem(String tenantId, String systemCode) {
        findBusinessSystem(tenantId, systemCode);
        // 仍有归属时不删，避免悬空 system_code
        long tools = repo.listBusinessTools(tenantId).stream()
                .filter(t -> systemCode.equals(t.systemCode())).count();
        if (tools > 0) {
            throw new CatalogException("SYSTEM_IN_USE", "system still has " + tools + " tool(s); unbind first");
        }
        repo.deleteBusinessSystem(tenantId, systemCode);
        log.info("delete business system tenant={} code={}", tenantId, systemCode);
    }

    public List<BusinessToolRecord> listTools(String tenantId) {
        return repo.listBusinessTools(tenantId);
    }

    public BusinessToolRecord findTool(String tenantId, String toolName) {
        return repo.findBusinessTool(tenantId, toolName)
                .orElseThrow(() -> new CatalogException("TOOL_NOT_FOUND", "tool not found: " + toolName));
    }

    public BusinessToolRecord registerTool(String tenantId, String toolName, String description, String kind, String config) {
        return registerTool(tenantId, toolName, description, kind, config, null);
    }

    public BusinessToolRecord registerTool(
            String tenantId,
            String toolName,
            String description,
            String kind,
            String config,
            ToolUpsertOptions options) {
        if (toolName == null || toolName.isBlank()) {
            throw new CatalogException("TOOL_NAME_REQUIRED", "toolName is required");
        }
        if (!toolName.matches("[A-Za-z0-9_.-]{1,128}")) {
            throw new CatalogException("TOOL_NAME_INVALID", "toolName must match [A-Za-z0-9_.-]{1,128}");
        }
        String k = kind == null ? BusinessToolRecord.KIND_IN_PROCESS : kind;
        String storedConfig = config;
        if (BusinessToolRecord.KIND_HTTP.equalsIgnoreCase(k)) {
            String method = extractMethod(config);
            com.sinosoft.amp.openapi.tool.ToolMeta meta =
                    com.sinosoft.amp.openapi.tool.ToolMeta.fromConfig(config, method);
            meta.validateForRegistration(toolName, method);
            validateHttpUrl(toolName, config);
            storedConfig = com.sinosoft.amp.openapi.tool.ToolMeta.mergeIntoConfig(config, meta);
            // 密钥只存引用，禁止把 Authorization 等明文写进 config.headers
            scrubSecretHeaders(toolName, storedConfig, options == null ? null : options.secretRef());
        } else if (BusinessToolRecord.KIND_MCP.equalsIgnoreCase(k)) {
            String tools = extractJsonField(config, "enableTools");
            if (tools == null || tools.isBlank()) {
                throw new CatalogException("MCP_TOOLS_REQUIRED", "enableTools is required for MCP tools");
            }
        }
        ToolUpsertOptions opt = options == null ? ToolUpsertOptions.defaults() : options;
        String lifecycle = opt.lifecycle() == null || opt.lifecycle().isBlank()
                ? BusinessToolRecord.STATUS_ENABLED
                : opt.lifecycle().trim().toUpperCase(java.util.Locale.ROOT);
        if (!java.util.Set.of(
                BusinessToolRecord.STATUS_DRAFT,
                BusinessToolRecord.STATUS_ENABLED,
                BusinessToolRecord.STATUS_DISABLED,
                BusinessToolRecord.STATUS_DEPRECATED).contains(lifecycle)) {
            throw new CatalogException("TOOL_LIFECYCLE_INVALID", "lifecycle must be DRAFT|ENABLED|DISABLED|DEPRECATED");
        }
        int version = 1;
        BusinessToolRecord existing = repo.findBusinessTool(tenantId, toolName).orElse(null);
        if (existing != null) {
            version = (existing.version() == null ? 1 : existing.version()) + 1;
        }
        BusinessToolRecord rec = new BusinessToolRecord(
                tenantId, toolName, description, k,
                lifecycle,
                storedConfig,
                version,
                opt.env() == null || opt.env().isBlank() ? BusinessToolRecord.ENV_PROD : opt.env(),
                opt.owner(),
                opt.secretRef(),
                opt.timeoutMs() == null ? 30_000 : opt.timeoutMs(),
                opt.maxRetries() == null ? 0 : opt.maxRetries(),
                opt.rateLimitQps(),
                opt.tags(),
                opt.updatedBy(),
                java.time.Instant.now(),
                1,
                null,
                opt.systemCode());
        repo.upsertBusinessTool(rec);
        log.info("register tool tenant={} name={} kind={} lifecycle={} v{} risk-validated",
                tenantId, toolName, k, lifecycle, version);
        return rec;
    }

    public BusinessToolRecord setToolLifecycle(
            String tenantId, String toolName, String lifecycle, String reason, String updatedBy) {
        BusinessToolRecord existing = findTool(tenantId, toolName);
        String st = lifecycle == null ? "" : lifecycle.trim().toUpperCase(java.util.Locale.ROOT);
        if (!java.util.Set.of(
                BusinessToolRecord.STATUS_DRAFT,
                BusinessToolRecord.STATUS_ENABLED,
                BusinessToolRecord.STATUS_DISABLED,
                BusinessToolRecord.STATUS_DEPRECATED).contains(st)) {
            throw new CatalogException("TOOL_LIFECYCLE_INVALID", "lifecycle must be DRAFT|ENABLED|DISABLED|DEPRECATED");
        }
        BusinessToolRecord rec = new BusinessToolRecord(
                existing.tenantId(), existing.toolName(), existing.description(), existing.kind(),
                st, existing.config(), existing.version(), existing.env(), existing.owner(),
                existing.secretRef(), existing.timeoutMs(), existing.maxRetries(), existing.rateLimitQps(),
                existing.tags(), updatedBy, java.time.Instant.now(), existing.configSchemaVer(),
                BusinessToolRecord.STATUS_DEPRECATED.equals(st) ? reason : null, existing.systemCode());
        repo.upsertBusinessTool(rec);
        log.info("tool lifecycle tenant={} name={} {} -> {}", tenantId, toolName, existing.status(), st);
        return rec;
    }

    public void hardDeleteTool(String tenantId, String toolName) {
        findTool(tenantId, toolName);
        repo.deleteBusinessTool(tenantId, toolName);
        log.info("hard delete tool tenant={} name={}", tenantId, toolName);
    }

    // ---- agent × tool binding ----

    public List<AgentToolBindingRecord> listToolBindings(String tenantId, String agentId) {
        return repo.listToolBindings(tenantId, agentId);
    }

    public void upsertToolBinding(AgentToolBindingRecord record) {
        if (record == null || record.toolName() == null || record.toolName().isBlank()) {
            throw new CatalogException("TOOL_NAME_REQUIRED", "binding toolName is required");
        }
        // 绑定目标工具必须存在，避免悬空
        findTool(record.tenantId(), record.toolName());
        repo.upsertToolBinding(record);
    }

    public void deleteToolBinding(String tenantId, String agentId, String toolName) {
        repo.deleteToolBinding(tenantId, agentId, toolName);
    }

    /**
     * Agent 可挂载工具：有绑定行则按绑定；无绑定行兼容旧模型（全部 ENABLED）。
     */
    public List<BusinessToolRecord> mountableTools(String tenantId, String agentId) {
        List<BusinessToolRecord> all = repo.listBusinessTools(tenantId).stream()
                .filter(BusinessToolRecord::isMountable)
                .toList();
        List<AgentToolBindingRecord> bindings = repo.listToolBindings(tenantId, agentId);
        if (bindings.isEmpty()) {
            return all;
        }
        java.util.Set<String> allow = new java.util.HashSet<>();
        for (AgentToolBindingRecord b : bindings) {
            if (b.enabled()) {
                allow.add(b.toolName());
            }
        }
        return all.stream().filter(t -> allow.contains(t.toolName())).toList();
    }

    // ---- secrets ----

    public void putToolSecret(String tenantId, String refName, String kind, String secretValue) {
        if (refName == null || refName.isBlank() || secretValue == null || secretValue.isBlank()) {
            throw new CatalogException("TOOL_SECRET_INVALID", "refName and secretValue are required");
        }
        repo.upsertToolSecret(tenantId, refName, kind, secretValue);
    }

    public void deleteToolSecret(String tenantId, String refName) {
        repo.deleteToolSecret(tenantId, refName);
    }

    public Optional<String> resolveToolSecret(String tenantId, String refName) {
        return repo.findToolSecret(tenantId, refName);
    }

    /** 禁止把疑似密钥明文写进 config.headers；应使用 secretRef。 */
    private static void scrubSecretHeaders(String toolName, String config, String secretRef) {
        if (config == null) {
            return;
        }
        String lower = config.toLowerCase(java.util.Locale.ROOT);
        boolean hasSecretHeader = lower.contains("\"authorization\"")
                || lower.contains("\"x-api-key\"")
                || lower.contains("\"api-key\"")
                || lower.contains("\"proxy-authorization\"");
        if (hasSecretHeader && (secretRef == null || secretRef.isBlank())) {
            throw new CatalogException("TOOL_SECRET_REF_REQUIRED",
                    "tool " + toolName + " must use secretRef instead of plaintext secret headers");
        }
    }

    private static void validateHttpUrl(String toolName, String config) {
        String url = extractJsonField(config, "url");
        if (url == null || url.isBlank()) {
            throw new CatalogException("HTTP_TOOL_URL_REQUIRED", "config.url is required for " + toolName);
        }
        String u = url.trim().toLowerCase(java.util.Locale.ROOT);
        if (!(u.startsWith("http://") || u.startsWith("https://"))) {
            throw new CatalogException("TOOL_URL_SCHEME_INVALID", "url must be http(s): " + toolName);
        }
        // SSRF 基线：禁 file/gopher 等；内网段默认放行（本地/联调），生产开 amp.tools.block-private-urls=true
        if (!Boolean.getBoolean("amp.tools.block-private-urls")) {
            return;
        }
        try {
            java.net.URI uri = java.net.URI.create(url);
            String host = uri.getHost();
            if (host == null) {
                throw new CatalogException("TOOL_URL_INVALID", "url host required: " + toolName);
            }
            String h = host.toLowerCase(java.util.Locale.ROOT);
            boolean privateHost = h.equals("localhost") || h.equals("127.0.0.1") || h.equals("0.0.0.0")
                    || h.equals("::1") || h.endsWith(".local") || h.endsWith(".internal")
                    || h.startsWith("10.") || h.startsWith("192.168.")
                    || h.matches("172\\.(1[6-9]|2[0-9]|3[01])\\..*");
            if (privateHost) {
                throw new CatalogException("TOOL_URL_PRIVATE_BLOCKED",
                        "private/loopback url blocked for " + toolName + " (amp.tools.block-private-urls=true)");
            }
        } catch (CatalogException e) {
            throw e;
        } catch (Exception e) {
            throw new CatalogException("TOOL_URL_INVALID", "invalid url for " + toolName + ": " + e.getMessage());
        }
    }

    /** 工具更新选项（生命周期 / 韧性 / 责任人）。 */
    public record ToolUpsertOptions(
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
        public static ToolUpsertOptions defaults() {
            return new ToolUpsertOptions(null, null, null, null, null, null, null, null, null, null);
        }

        public static ToolUpsertOptions of(
                String lifecycle, String env, String owner, String secretRef,
                Integer timeoutMs, Integer maxRetries, Integer rateLimitQps,
                String tags, String updatedBy, String systemCode) {
            return new ToolUpsertOptions(lifecycle, env, owner, secretRef, timeoutMs, maxRetries, rateLimitQps, tags, updatedBy, systemCode);
        }
    }

    private static String extractMethod(String config) {
        String m = extractJsonField(config, "method");
        return m == null || m.isBlank() ? "POST" : m.trim();
    }

    private static String extractJsonField(String config, String field) {
        if (config == null || config.isBlank()) {
            return null;
        }
        try {
            com.fasterxml.jackson.databind.JsonNode node =
                    new com.fasterxml.jackson.databind.ObjectMapper().readTree(config);
            if (node != null && node.hasNonNull(field)) {
                return node.get(field).asText();
            }
        } catch (Exception ignored) {
            // fall through
        }
        return null;
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
