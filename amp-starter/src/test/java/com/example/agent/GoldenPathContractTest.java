package com.example.agent;

import com.example.agent.runtime.HarnessFactory;
import com.example.agent.session.SessionContext;
import com.example.agent.session.SessionContextException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 金路径契约子集：session 校验 + 权限 "*"→null（不启 Spring / 不连 Redis）。
 */
class GoldenPathContractTest {

    @Test
    void sessionIdEmptyRejected() {
        assertThrows(SessionContextException.class,
                () -> SessionContext.require("acme", "u1", ""));
    }

    @Test
    void sessionIdBadCharsetRejected() {
        assertThrows(SessionContextException.class,
                () -> SessionContext.require("acme", "u1", "bad:sid"));
    }

    @Test
    void sessionOkScopedUser() {
        SessionContext.SessionKey key = SessionContext.require("acme", "u1", "sess-01");
        assertEquals("acme_u1", key.scopedUserId());
        assertEquals("sess-01", key.sessionId());
    }

    @Test
    void wildcardRuleContentMapsToNull() {
        // 坑：ToolBase.matchRule 仅 ruleContent==null 才匹配非文件工具；"*" 不是通配
        assertNull(HarnessFactory.normalizeRuleContent("*"));
        assertNull(HarnessFactory.normalizeRuleContent("  * "));
        assertNull(HarnessFactory.normalizeRuleContent(null));
        assertNull(HarnessFactory.normalizeRuleContent(""));
        assertEquals("target==demo", HarnessFactory.normalizeRuleContent("target==demo"));
    }

    @Test
    void demoPermissionUsesNormalizedContent() {
        var ctx = HarnessFactory.demoPermissionContext();
        // 构建不抛异常即表示 ALLOW/ASK 规则已注入
        org.junit.jupiter.api.Assertions.assertNotNull(ctx);
    }
}
