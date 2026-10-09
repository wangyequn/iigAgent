package com.sinosoft.amp.openapi.agent;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolPolicyPropertiesTest {

    @Test
    void defaultDenyIsEmptyWhenDangerousFacesDisabled() {
        // 探索/危险面（shell/filesystem/memory/session/web/agent_spawn）在构建期 disableXxx
        // 摘除注册，工具不存在；对未注册工具下发 deny 只会触发 harness "unknown tool" WARN，
        // 因此默认 deny 名单为空，封锁真实注册的工具用 extra-deny。
        ToolPolicyProperties p = new ToolPolicyProperties(null, null, null, false, false, false);
        assertTrue(p.allowlistMode() == false || ToolPolicyProperties.MODE_DENY.equalsIgnoreCase(p.mode()));
        assertEquals(List.of(), p.effectiveDeny());
    }

    @Test
    void allowlistModeUsesMinimalAllow() {
        ToolPolicyProperties p = new ToolPolicyProperties("allow", null, null, false, false, false);
        assertTrue(p.allowlistMode());
        assertEquals(ToolPolicyProperties.DEFAULT_ALLOW, p.effectiveAllow());
    }

    @Test
    void extraDenyIsTheBlockingChannel() {
        ToolPolicyProperties p = new ToolPolicyProperties("deny", null, List.of("custom_tool"), false, false, false);
        assertEquals(List.of("custom_tool"), p.effectiveDeny());
    }

    @Test
    void toolPolicyApplierSetsAllowOrDeny() {
        var allowCfg = new ToolPolicyApplier(
                new ToolPolicyProperties("allow", List.of("safe_lookup"), null, false, false, false)).apply();
        assertEquals(List.of("safe_lookup"), allowCfg.getAllow());

        var denyCfg = new ToolPolicyApplier(
                new ToolPolicyProperties("deny", null, List.of("dangerous_tool"), false, false, false)).apply();
        assertEquals(List.of("dangerous_tool"), denyCfg.getDeny());
    }
}
