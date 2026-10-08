package com.sinosoft.amp.openapi.session;

import io.agentscope.core.agent.RuntimeContext;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SessionContextTest {

    @Test
    void requiresAllFields() {
        assertThrows(SessionContextException.class,
                () -> SessionContext.require("acme", "", "s1"));
        assertThrows(SessionContextException.class,
                () -> SessionContext.require("acme", "u1", ""));
        assertThrows(SessionContextException.class,
                () -> SessionContext.require("", "u1", "s1"));
    }

    @Test
    void rejectsInvalidSessionId() {
        assertThrows(SessionContextException.class,
                () -> SessionContext.require("acme", "u1", "bad session!"));
        assertThrows(SessionContextException.class,
                () -> SessionContext.require("acme", "u1", "x".repeat(129)));
    }

    @Test
    void scopesUserWithTenant() {
        RuntimeContext ctx = SessionContext.require("acme", "u1", "agt_demo_1");
        assertEquals("acme_u1", ctx.getUserId());
        assertEquals("agt_demo_1", ctx.getSessionId());
    }
}
