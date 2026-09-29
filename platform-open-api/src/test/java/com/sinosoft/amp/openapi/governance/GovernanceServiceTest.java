package com.sinosoft.amp.openapi.governance;

import com.sinosoft.amp.openapi.catalog.CatalogException;
import com.sinosoft.amp.openapi.tool.BusinessToolRecord;
import com.sinosoft.amp.openapi.tool.OrderBusinessTools;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GovernanceServiceTest {

    private InMemoryGovernanceRepository repo;
    private GovernanceService service;

    @BeforeEach
    void setUp() {
        repo = new InMemoryGovernanceRepository();
        service = new GovernanceService(repo);
        service.upsertRule("acme", "demo", "place_order", "ASK", "*", "platform");
        service.upsertRule("acme", "demo", "query_orders", "ALLOW", "*", "platform");
        service.setMode("acme", "demo", "DEFAULT");
        service.registerTool("acme", "place_order", "危险下单", "IN_PROCESS", null);
    }

    @Test
    void buildPermissionContextHasAskAndAllow() {
        var ctx = service.buildPermissionContext("acme", "demo");
        assertTrue(ctx.getAskRules().containsKey("place_order"));
        assertTrue(ctx.getAllowRules().containsKey("query_orders"));
        assertEquals(io.agentscope.core.permission.PermissionMode.DEFAULT, ctx.getMode());
    }

    @Test
    void invalidBehaviorRejected() {
        assertThrows(CatalogException.class,
                () -> service.upsertRule("acme", "demo", "x", "MAYBE", "*", null));
    }

    @Test
    void approvalDecideIsIdempotent() {
        ApprovalItem item = new ApprovalItem(
                "a1", "acme", "demo", "u1", "s1", "r1", "c1", "place_order",
                "{\"product\":\"book\"}", ApprovalItem.PENDING, null, null, Instant.now(), null);
        service.openApproval(item);
        ApprovalItem decided = service.decide("a1", true, "ok", "key-1");
        assertEquals(ApprovalItem.APPROVED, decided.status());
        CatalogException ex = assertThrows(CatalogException.class,
                () -> service.decide("a1", false, "again", "key-1"));
        assertEquals("APPROVAL_ALREADY_RESOLVED", ex.code());
    }

    @Test
    void mcpRegistrationValidatesTransport() {
        assertThrows(CatalogException.class,
                () -> service.registerMcp("acme", "m1", "bad", null, null, null, null));
        McpServerRecord ok = service.registerMcp("acme", "m1", "stdio", "npx", "mcp-server-x", null, "tool_a");
        assertEquals("REGISTERED", ok.status());
    }

    @Test
    void mcpConnectBuildsConfigAndFailsSoft() {
        McpConnectService connect = new McpConnectService(service);
        service.registerMcp("acme", "bad-http", "streamable_http", null, null, "http://127.0.0.1:1", "t1");
        Map<String, Object> probe = connect.probe("acme", "bad-http");
        assertEquals("FAILED", probe.get("status"));
        var map = connect.buildToolsConfigMap("acme");
        assertTrue(map.containsKey("m1") || map.containsKey("bad-http"));
    }

    @Test
    void orderToolsProduceJson() {
        var tools = new com.sinosoft.amp.openapi.tool.OrderToolset();
        String q = tools.queryOrders(null);
        String p = tools.placeOrder("书", 2);
        assertTrue(q.contains("ORD-"));
        assertTrue(p.contains("CREATED"));
        assertTrue(tools.placeOrder(null, 1).contains("error") || tools.placeOrder(null, 1).contains("required"));
    }

    /** 内存实现，单测不依赖 PG。 */
    static class InMemoryGovernanceRepository implements GovernanceRepository {
        private final List<PermissionRuleRecord> rules = new ArrayList<>();
        private final Map<String, String> modes = new HashMap<>();
        private final List<McpServerRecord> mcp = new ArrayList<>();
        private final List<BusinessToolRecord> tools = new ArrayList<>();
        private final Map<String, ApprovalItem> approvals = new HashMap<>();
        private final Map<String, String> idem = new HashMap<>();

        @Override
        public List<PermissionRuleRecord> listPermissionRules(String tenantId, String agentId) {
            return rules.stream()
                    .filter(r -> r.tenantId().equals(tenantId) && r.agentId().equals(agentId))
                    .toList();
        }

        @Override
        public void upsertPermissionRule(PermissionRuleRecord rule) {
            rules.removeIf(r -> r.tenantId().equals(rule.tenantId()) && r.agentId().equals(rule.agentId())
                    && r.toolName().equals(rule.toolName())
                    && r.behavior().equals(rule.behavior())
                    && r.ruleContent().equals(rule.ruleContent()));
            rules.add(rule);
        }

        @Override
        public void deletePermissionRule(String tenantId, String agentId, String toolName, String behavior, String ruleContent) {
            rules.removeIf(r -> r.tenantId().equals(tenantId) && r.agentId().equals(agentId)
                    && r.toolName().equals(toolName) && r.behavior().equals(behavior)
                    && r.ruleContent().equals(ruleContent));
        }

        @Override
        public String findPermissionMode(String tenantId, String agentId) {
            return modes.getOrDefault(tenantId + "|" + agentId, "DEFAULT");
        }

        @Override
        public void upsertPermissionMode(String tenantId, String agentId, String mode) {
            modes.put(tenantId + "|" + agentId, mode);
        }

        @Override
        public List<McpServerRecord> listMcpServers(String tenantId) {
            return mcp.stream().filter(m -> m.tenantId().equals(tenantId)).toList();
        }

        @Override
        public Optional<McpServerRecord> findMcpServer(String tenantId, String name) {
            return listMcpServers(tenantId).stream().filter(m -> m.name().equals(name)).findFirst();
        }

        @Override
        public void upsertMcpServer(McpServerRecord record) {
            mcp.removeIf(m -> m.tenantId().equals(record.tenantId()) && m.name().equals(record.name()));
            mcp.add(record);
        }

        @Override
        public List<BusinessToolRecord> listBusinessTools(String tenantId) {
            return tools.stream().filter(t -> t.tenantId().equals(tenantId)).toList();
        }

        @Override
        public void upsertBusinessTool(BusinessToolRecord record) {
            tools.removeIf(t -> t.tenantId().equals(record.tenantId()) && t.toolName().equals(record.toolName()));
            tools.add(record);
        }

        @Override
        public Optional<ApprovalItem> findApproval(String id) {
            return Optional.ofNullable(approvals.get(id));
        }

        @Override
        public Optional<ApprovalItem> findApprovalByIdempotency(String idempotencyKey) {
            if (idempotencyKey == null) return Optional.empty();
            String id = idem.get(idempotencyKey);
            return id == null ? Optional.empty() : findApproval(id);
        }

        @Override
        public List<ApprovalItem> listPendingApprovals(String tenantId, String agentId, String sessionId) {
            return approvals.values().stream()
                    .filter(a -> a.tenantId().equals(tenantId) && a.agentId().equals(agentId)
                            && a.sessionId().equals(sessionId) && ApprovalItem.PENDING.equals(a.status()))
                    .toList();
        }

        @Override
        public void insertApproval(ApprovalItem item) {
            approvals.put(item.id(), item);
            if (item.idempotencyKey() != null) {
                idem.put(item.idempotencyKey(), item.id());
            }
        }

        @Override
        public void updateApprovalStatus(String id, String status, String note, Instant resolvedAt) {
            ApprovalItem old = approvals.get(id);
            if (old == null) return;
            approvals.put(id, new ApprovalItem(
                    old.id(), old.tenantId(), old.agentId(), old.userId(), old.sessionId(),
                    old.replyId(), old.toolCallId(), old.toolName(), old.toolInput(),
                    status, note, old.idempotencyKey(), old.createdAt(), resolvedAt));
        }

        @Override
        public void bindIdempotency(String id, String idempotencyKey) {
            ApprovalItem old = approvals.get(id);
            if (old == null || idempotencyKey == null) return;
            approvals.put(id, new ApprovalItem(
                    old.id(), old.tenantId(), old.agentId(), old.userId(), old.sessionId(),
                    old.replyId(), old.toolCallId(), old.toolName(), old.toolInput(),
                    old.status(), old.decisionNote(), idempotencyKey, old.createdAt(), old.resolvedAt()));
            idem.put(idempotencyKey, id);
        }
    }
}
