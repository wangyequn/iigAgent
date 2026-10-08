package com.sinosoft.amp.openapi.governance;

import com.sinosoft.amp.openapi.tool.SampleToolset;
import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionEngine;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.Toolkit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 回归：库里的通配符 "*" 必须映射为 null 才能被 AgentScope 的 matchRule 命中。
 * 若回退成 "*"，ALLOW/ASK 规则全部失效（工具一律落到 mode 默认），本测试会失败。
 */
class PermissionRuleMatchingTest {

    private GovernanceService service;

    @BeforeEach
    void setUp() {
        service = new GovernanceService(new GovernanceServiceTest.InMemoryGovernanceRepository());
        service.upsertRule("acme", "demo", "safe_lookup", "ALLOW", "*", "platform");
        service.upsertRule("acme", "demo", "dangerous_write", "ASK", "*", "platform");
        service.upsertRule("acme", "demo", "write_file", "DENY", "*", "platform");
        service.setMode("acme", "demo", "DEFAULT");
    }

    private static ToolBase tool(String name) {
        Toolkit tk = new Toolkit();
        tk.registerTool(new SampleToolset());
        tk.registerTool(new UnlistedToolset());
        AgentTool t = tk.getTool(name);
        assertTrue(t instanceof ToolBase, "expected ToolBase for " + name);
        return (ToolBase) t;
    }

    /** 故意不配权限规则的工具，用于验证 mode 兜底。 */
    static class UnlistedToolset {
        @io.agentscope.core.tool.Tool(
                name = "unlisted_tool",
                description = "no rule configured",
                readOnly = true,
                concurrencySafe = true)
        public String run(@io.agentscope.core.tool.ToolParam(name = "arg", required = false) Object arg) {
            return "ok";
        }
    }

    private static PermissionBehavior decide(PermissionEngine engine, String toolName) {
        return engine.checkPermission(tool(toolName), Map.of("key", "k1")).block().getBehavior();
    }

    @Test
    void wildcardStoredContentMapsToNull() {
        PermissionContextState ctx = service.buildPermissionContext("acme", "demo");
        var rule = ctx.getAllowRules().get("safe_lookup").get(0);
        assertNull(rule.ruleContent(), "通配符必须映射为 null，否则匹配不上");
    }

    @Test
    void allowRuleIsHonouredUnderDefaultMode() {
        PermissionEngine engine = new PermissionEngine(service.buildPermissionContext("acme", "demo"));
        assertEquals(PermissionBehavior.ALLOW, decide(engine, "safe_lookup"));
    }

    @Test
    void askRuleIsHonouredUnderDefaultMode() {
        PermissionEngine engine = new PermissionEngine(service.buildPermissionContext("acme", "demo"));
        assertEquals(PermissionBehavior.ASK, decide(engine, "dangerous_write"));
    }

    @Test
    void unknownToolFallsBackToModeDefault() {
        PermissionEngine engine = new PermissionEngine(service.buildPermissionContext("acme", "demo"));
        // 无规则 → DEFAULT 模式兜底为 ASK
        assertEquals(PermissionBehavior.ASK, decide(engine, "unlisted_tool"));
    }

    @Test
    void starContentWouldBreakMatching() {
        // 固化框架行为：非 null 内容匹配失败 → 说明为何必须映射为 null
        ToolBase t = tool("safe_lookup");
        assertTrue(t.matchRule(null, Map.of()), "null 应匹配");
        assertEquals(false, t.matchRule("*", Map.of()), "'*' 不应被当作通配符");
    }

    @Test
    void switchingBehaviorReplacesInsteadOfStacking() {
        // 先把 safe_lookup 改成 ASK，再改回 ALLOW；不能两条并存（ASK 会盖掉 ALLOW）
        service.upsertRule("acme", "demo", "safe_lookup", "ASK", "*", "platform");
        service.upsertRule("acme", "demo", "safe_lookup", "ALLOW", "*", "platform");
        var rules = service.listRules("acme", "demo").stream()
                .filter(r -> "safe_lookup".equals(r.toolName()))
                .toList();
        assertEquals(1, rules.size(), "同一工具应只保留一种行为：" + rules);
        assertEquals("ALLOW", rules.get(0).behavior());

        PermissionEngine engine = new PermissionEngine(service.buildPermissionContext("acme", "demo"));
        assertEquals(PermissionBehavior.ALLOW, decide(engine, "safe_lookup"));
    }
}
