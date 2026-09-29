package com.sinosoft.amp.openapi.governance;

import com.sinosoft.amp.openapi.tool.BusinessToolRecord;

import java.util.List;
import java.util.Optional;

/**
 * S4 治理目录：权限规则 / 权限 Mode / MCP 登记 / 业务工具 / 审批项。
 */
public interface GovernanceRepository {

    // --- permission ---
    List<PermissionRuleRecord> listPermissionRules(String tenantId, String agentId);

    void upsertPermissionRule(PermissionRuleRecord rule);

    void deletePermissionRule(String tenantId, String agentId, String toolName, String behavior, String ruleContent);

    String findPermissionMode(String tenantId, String agentId);

    void upsertPermissionMode(String tenantId, String agentId, String mode);

    // --- mcp ---
    List<McpServerRecord> listMcpServers(String tenantId);

    Optional<McpServerRecord> findMcpServer(String tenantId, String name);

    void upsertMcpServer(McpServerRecord record);

    // --- business tools ---
    List<BusinessToolRecord> listBusinessTools(String tenantId);

    void upsertBusinessTool(BusinessToolRecord record);

    // --- approval ---
    Optional<ApprovalItem> findApproval(String id);

    Optional<ApprovalItem> findApprovalByIdempotency(String idempotencyKey);

    List<ApprovalItem> listPendingApprovals(String tenantId, String agentId, String sessionId);

    void insertApproval(ApprovalItem item);

    void updateApprovalStatus(String id, String status, String note, java.time.Instant resolvedAt);

    void bindIdempotency(String id, String idempotencyKey);
}
