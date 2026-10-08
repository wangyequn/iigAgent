package com.sinosoft.amp.openapi.ops;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuditFilterTest {

    @Test
    void auditsAdminMutationsAndConfirm() {
        assertTrue(AuditFilter.shouldAudit("POST", "/api/v1/admin/skills"));
        assertTrue(AuditFilter.shouldAudit("POST", "/api/v1/agents/demo/sessions/s1/confirm"));
        assertTrue(AuditFilter.shouldAudit("POST", "/api/v1/admin/agents/demo/versions/2/publish"));
    }

    @Test
    void skipsReadsAndHealth() {
        assertFalse(AuditFilter.shouldAudit("GET", "/api/v1/admin/skills"));
        assertFalse(AuditFilter.shouldAudit("GET", "/api/v1/health/ready"));
        assertFalse(AuditFilter.shouldAudit("POST", "/api/v1/agents/demo/messages"));
    }

    @Test
    void resolvesActionNames() {
        assertEquals("HITL_CONFIRM", AuditFilter.resolveAction("POST", "/api/v1/agents/demo/sessions/s/confirm"));
        assertEquals("VERSION_PUBLISH", AuditFilter.resolveAction("POST", "/api/v1/admin/agents/demo/versions/1/publish"));
        assertEquals("SKILL_APPROVE", AuditFilter.resolveAction("POST", "/api/v1/admin/skills/abc/approve"));
        assertEquals("HITL_DECIDE", AuditFilter.resolveAction("POST", "/api/v1/admin/approvals/x/decide"));
    }
}
