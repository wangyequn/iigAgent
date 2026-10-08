package com.sinosoft.amp.openapi.agent;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolPolicyPropertiesTest {

    @Test
    void defaultModeDeniesExploratoryTools() {
        ToolPolicyProperties p = new ToolPolicyProperties(null, null, null, false, false, false);
        assertTrue(p.allowlistMode() == false || ToolPolicyProperties.MODE_DENY.equalsIgnoreCase(p.mode()));
        List<String> deny = p.effectiveDeny();
        assertTrue(deny.contains("agent_spawn"));
        assertTrue(deny.contains("web_search"));
        assertTrue(deny.contains("shell_execute"));
    }

    @Test
    void enablingAgentSpawnRemovesFromDeny() {
        ToolPolicyProperties p = new ToolPolicyProperties("deny", null, null, true, false, false);
        assertFalse(p.effectiveDeny().contains("agent_spawn"));
    }

    @Test
    void allowlistModeUsesMinimalAllow() {
        ToolPolicyProperties p = new ToolPolicyProperties("allow", null, null, false, false, false);
        assertTrue(p.allowlistMode());
        assertEquals(ToolPolicyProperties.DEFAULT_ALLOW, p.effectiveAllow());
    }

    @Test
    void extraDenyAppended() {
        ToolPolicyProperties p = new ToolPolicyProperties("deny", null, List.of("custom_tool"), false, false, false);
        assertTrue(p.effectiveDeny().contains("custom_tool"));
    }

    @Test
    void toolPolicyApplierSetsAllowOrDeny() {
        var allowCfg = new ToolPolicyApplier(
                new ToolPolicyProperties("allow", List.of("safe_lookup"), null, false, false, false)).apply();
        assertEquals(List.of("safe_lookup"), allowCfg.getAllow());

        var denyCfg = new ToolPolicyApplier(
                new ToolPolicyProperties("deny", null, null, false, false, false)).apply();
        assertTrue(denyCfg.getDeny().contains("agent_spawn"));
    }
}
