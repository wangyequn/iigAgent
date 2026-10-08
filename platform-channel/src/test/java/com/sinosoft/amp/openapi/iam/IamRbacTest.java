package com.sinosoft.amp.openapi.iam;

import com.sinosoft.amp.openapi.security.AuthContext;
import com.sinosoft.amp.openapi.security.AuthProperties;
import com.sinosoft.amp.openapi.security.UnauthorizedException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IamRbacTest {

    private IamService iam;
    private RbacGuard guard;

    @BeforeEach
    void setUp() {
        iam = new IamService();
        guard = new RbacGuard(iam);
    }

    @AfterEach
    void tearDown() {
        AuthContext.clear();
    }

    @Test
    void agentCallerDeniedOrchestrationWrite() {
        AuthContext.set(new AuthProperties.KeyEntry("k", "acme", List.of("demo"), List.of("open")));
        // 无 X-User-Roles → 默认 agent_caller
        assertThrows(UnauthorizedException.class, () -> guard.require("orch:write"));
    }

    @Test
    void adminScopeAllowsAll() {
        AuthContext.set(new AuthProperties.KeyEntry("k", "acme", List.of("demo"), List.of("open", "admin")));
        guard.require("orch:write");
        guard.require("iam:admin");
        guard.require("billing:read");
    }

    @Test
    void developerRoleCanRunWorkflows() {
        AuthContext.set(new AuthProperties.KeyEntry("k", "acme", List.of("demo"), List.of("open")));
        iam.assignRole("acme", "alice", IamService.ROLE_DEVELOPER);
        assertTrue(iam.hasPermission("acme", "alice", Set.of("developer"), "orch:run"));
        assertTrue(iam.hasPermission("acme", "alice", Set.of("developer"), "orch:write"));
        assertFalse(iam.hasPermission("acme", "alice", Set.of("developer"), "skill:review"));
    }

    @Test
    void auditorReadOnlyBilling() {
        assertTrue(iam.hasPermission("acme", "bob", Set.of("auditor"), "billing:read"));
        assertFalse(iam.hasPermission("acme", "bob", Set.of("auditor"), "orch:write"));
        assertFalse(iam.hasPermission("acme", "bob", Set.of("auditor"), "iam:admin"));
    }

    @Test
    void unknownRoleRejected() {
        assertThrows(UnauthorizedException.class,
                () -> iam.assignRole("acme", "x", "superuser"));
    }
}
