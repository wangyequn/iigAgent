package com.sinosoft.amp.openapi.governance;

import com.sinosoft.amp.openapi.tool.BusinessToolRecord;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public class JdbcGovernanceRepository implements GovernanceRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcGovernanceRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static Timestamp ts(Instant i) {
        return i == null ? null : Timestamp.from(i);
    }

    private static Instant inst(Timestamp t) {
        return t == null ? null : t.toInstant();
    }

    @Override
    public List<PermissionRuleRecord> listPermissionRules(String tenantId, String agentId) {
        return jdbc.query(
                """
                SELECT tenant_id, agent_id, tool_name, rule_content, behavior, source
                FROM permission_rule
                WHERE tenant_id = :tenantId AND agent_id = :agentId
                ORDER BY tool_name, behavior
                """,
                new MapSqlParameterSource().addValue("tenantId", tenantId).addValue("agentId", agentId),
                (rs, i) -> new PermissionRuleRecord(
                        rs.getString("tenant_id"), rs.getString("agent_id"), rs.getString("tool_name"),
                        rs.getString("rule_content"), rs.getString("behavior"), rs.getString("source")));
    }

    @Override
    public void upsertPermissionRule(PermissionRuleRecord rule) {
        jdbc.update(
                """
                INSERT INTO permission_rule (tenant_id, agent_id, tool_name, rule_content, behavior, source, created_at)
                VALUES (:tenantId, :agentId, :toolName, :ruleContent, :behavior, :source, :createdAt)
                ON CONFLICT (tenant_id, agent_id, tool_name, rule_content, behavior) DO UPDATE
                SET source = EXCLUDED.source
                """,
                new MapSqlParameterSource()
                        .addValue("tenantId", rule.tenantId())
                        .addValue("agentId", rule.agentId())
                        .addValue("toolName", rule.toolName())
                        .addValue("ruleContent", rule.ruleContent() == null ? "*" : rule.ruleContent())
                        .addValue("behavior", rule.behavior())
                        .addValue("source", rule.source() == null ? "platform" : rule.source())
                        .addValue("createdAt", ts(Instant.now())));
    }

    @Override
    public void deletePermissionRule(String tenantId, String agentId, String toolName, String behavior, String ruleContent) {
        jdbc.update(
                """
                DELETE FROM permission_rule
                WHERE tenant_id = :tenantId AND agent_id = :agentId
                  AND tool_name = :toolName AND behavior = :behavior
                  AND rule_content = :ruleContent
                """,
                new MapSqlParameterSource()
                        .addValue("tenantId", tenantId)
                        .addValue("agentId", agentId)
                        .addValue("toolName", toolName)
                        .addValue("behavior", behavior)
                        .addValue("ruleContent", ruleContent));
    }

    @Override
    public String findPermissionMode(String tenantId, String agentId) {
        List<String> rows = jdbc.queryForList(
                """
                SELECT mode FROM agent_permission_mode
                WHERE tenant_id = :tenantId AND agent_id = :agentId
                """,
                new MapSqlParameterSource().addValue("tenantId", tenantId).addValue("agentId", agentId),
                String.class);
        return rows.isEmpty() ? "DEFAULT" : rows.get(0);
    }

    @Override
    public void upsertPermissionMode(String tenantId, String agentId, String mode) {
        jdbc.update(
                """
                INSERT INTO agent_permission_mode (tenant_id, agent_id, mode, updated_at)
                VALUES (:tenantId, :agentId, :mode, :updatedAt)
                ON CONFLICT (tenant_id, agent_id) DO UPDATE
                SET mode = EXCLUDED.mode, updated_at = EXCLUDED.updated_at
                """,
                new MapSqlParameterSource()
                        .addValue("tenantId", tenantId)
                        .addValue("agentId", agentId)
                        .addValue("mode", mode)
                        .addValue("updatedAt", ts(Instant.now())));
    }

    @Override
    public List<McpServerRecord> listMcpServers(String tenantId) {
        return jdbc.query(
                """
                SELECT tenant_id, name, transport, command, args, url, enable_tools, status
                FROM mcp_server WHERE tenant_id = :tenantId ORDER BY name
                """,
                new MapSqlParameterSource().addValue("tenantId", tenantId),
                (rs, i) -> new McpServerRecord(
                        rs.getString("tenant_id"), rs.getString("name"), rs.getString("transport"),
                        rs.getString("command"), rs.getString("args"), rs.getString("url"),
                        rs.getString("enable_tools"), rs.getString("status")));
    }

    @Override
    public Optional<McpServerRecord> findMcpServer(String tenantId, String name) {
        return listMcpServers(tenantId).stream().filter(m -> m.name().equals(name)).findFirst();
    }

    @Override
    public void upsertMcpServer(McpServerRecord r) {
        jdbc.update(
                """
                INSERT INTO mcp_server (tenant_id, name, transport, command, args, url, enable_tools, status, created_at)
                VALUES (:tenantId, :name, :transport, :command, :args, :url, :enableTools, :status, :createdAt)
                ON CONFLICT (tenant_id, name) DO UPDATE
                SET transport = EXCLUDED.transport,
                    command = EXCLUDED.command,
                    args = EXCLUDED.args,
                    url = EXCLUDED.url,
                    enable_tools = EXCLUDED.enable_tools,
                    status = EXCLUDED.status
                """,
                new MapSqlParameterSource()
                        .addValue("tenantId", r.tenantId())
                        .addValue("name", r.name())
                        .addValue("transport", r.transport())
                        .addValue("command", r.command())
                        .addValue("args", r.args())
                        .addValue("url", r.url())
                        .addValue("enableTools", r.enableTools())
                        .addValue("status", r.status() == null ? McpServerRecord.STATUS_REGISTERED : r.status())
                        .addValue("createdAt", ts(Instant.now())));
    }

    @Override
    public List<BusinessToolRecord> listBusinessTools(String tenantId) {
        return jdbc.query(
                """
                SELECT tenant_id, tool_name, description, kind, status, config
                FROM business_tool WHERE tenant_id = :tenantId ORDER BY tool_name
                """,
                new MapSqlParameterSource().addValue("tenantId", tenantId),
                (rs, i) -> new BusinessToolRecord(
                        rs.getString("tenant_id"), rs.getString("tool_name"), rs.getString("description"),
                        rs.getString("kind"), rs.getString("status"), rs.getString("config")));
    }

    @Override
    public void upsertBusinessTool(BusinessToolRecord t) {
        jdbc.update(
                """
                INSERT INTO business_tool (tenant_id, tool_name, description, kind, status, config, created_at)
                VALUES (:tenantId, :toolName, :description, :kind, :status, :config, :createdAt)
                ON CONFLICT (tenant_id, tool_name) DO UPDATE
                SET description = EXCLUDED.description,
                    kind = EXCLUDED.kind,
                    status = EXCLUDED.status,
                    config = EXCLUDED.config
                """,
                new MapSqlParameterSource()
                        .addValue("tenantId", t.tenantId())
                        .addValue("toolName", t.toolName())
                        .addValue("description", t.description())
                        .addValue("kind", t.kind() == null ? BusinessToolRecord.KIND_IN_PROCESS : t.kind())
                        .addValue("status", t.status() == null ? BusinessToolRecord.STATUS_ENABLED : t.status())
                        .addValue("config", t.config())
                        .addValue("createdAt", ts(Instant.now())));
    }

    @Override
    public Optional<ApprovalItem> findApproval(String id) {
        List<ApprovalItem> rows = jdbc.query(
                """
                SELECT id, tenant_id, agent_id, user_id, session_id, reply_id, tool_call_id, tool_name,
                       tool_input, status, decision_note, idempotency_key, created_at, resolved_at
                FROM approval_item WHERE id = :id
                """,
                new MapSqlParameterSource().addValue("id", id),
                this::mapApproval);
        return rows.stream().findFirst();
    }

    @Override
    public Optional<ApprovalItem> findApprovalByIdempotency(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return Optional.empty();
        }
        List<ApprovalItem> rows = jdbc.query(
                """
                SELECT id, tenant_id, agent_id, user_id, session_id, reply_id, tool_call_id, tool_name,
                       tool_input, status, decision_note, idempotency_key, created_at, resolved_at
                FROM approval_item WHERE idempotency_key = :idempotencyKey
                """,
                new MapSqlParameterSource().addValue("idempotencyKey", idempotencyKey),
                this::mapApproval);
        return rows.stream().findFirst();
    }

    @Override
    public List<ApprovalItem> listPendingApprovals(String tenantId, String agentId, String sessionId) {
        return jdbc.query(
                """
                SELECT id, tenant_id, agent_id, user_id, session_id, reply_id, tool_call_id, tool_name,
                       tool_input, status, decision_note, idempotency_key, created_at, resolved_at
                FROM approval_item
                WHERE tenant_id = :tenantId AND agent_id = :agentId AND session_id = :sessionId
                  AND status = 'PENDING'
                ORDER BY created_at
                """,
                new MapSqlParameterSource()
                        .addValue("tenantId", tenantId)
                        .addValue("agentId", agentId)
                        .addValue("sessionId", sessionId),
                this::mapApproval);
    }

    @Override
    public void insertApproval(ApprovalItem item) {
        jdbc.update(
                """
                INSERT INTO approval_item
                (id, tenant_id, agent_id, user_id, session_id, reply_id, tool_call_id, tool_name,
                 tool_input, status, decision_note, idempotency_key, created_at, resolved_at)
                VALUES
                (:id, :tenantId, :agentId, :userId, :sessionId, :replyId, :toolCallId, :toolName,
                 :toolInput, :status, :decisionNote, :idempotencyKey, :createdAt, :resolvedAt)
                """,
                new MapSqlParameterSource()
                        .addValue("id", item.id())
                        .addValue("tenantId", item.tenantId())
                        .addValue("agentId", item.agentId())
                        .addValue("userId", item.userId())
                        .addValue("sessionId", item.sessionId())
                        .addValue("replyId", item.replyId())
                        .addValue("toolCallId", item.toolCallId())
                        .addValue("toolName", item.toolName())
                        .addValue("toolInput", item.toolInput())
                        .addValue("status", item.status())
                        .addValue("decisionNote", item.decisionNote())
                        .addValue("idempotencyKey", item.idempotencyKey())
                        .addValue("createdAt", ts(item.createdAt() == null ? Instant.now() : item.createdAt()))
                        .addValue("resolvedAt", ts(item.resolvedAt())));
    }

    @Override
    public void updateApprovalStatus(String id, String status, String note, Instant resolvedAt) {
        jdbc.update(
                """
                UPDATE approval_item
                SET status = :status, decision_note = :note, resolved_at = :resolvedAt
                WHERE id = :id
                """,
                new MapSqlParameterSource()
                        .addValue("status", status)
                        .addValue("note", note)
                        .addValue("resolvedAt", ts(resolvedAt))
                        .addValue("id", id));
    }

    @Override
    public void bindIdempotency(String id, String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return;
        }
        jdbc.update(
                "UPDATE approval_item SET idempotency_key = :key WHERE id = :id",
                new MapSqlParameterSource().addValue("key", idempotencyKey).addValue("id", id));
    }

    private ApprovalItem mapApproval(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        return new ApprovalItem(
                rs.getString("id"),
                rs.getString("tenant_id"),
                rs.getString("agent_id"),
                rs.getString("user_id"),
                rs.getString("session_id"),
                rs.getString("reply_id"),
                rs.getString("tool_call_id"),
                rs.getString("tool_name"),
                rs.getString("tool_input"),
                rs.getString("status"),
                rs.getString("decision_note"),
                rs.getString("idempotency_key"),
                inst(rs.getTimestamp("created_at")),
                inst(rs.getTimestamp("resolved_at")));
    }
}
