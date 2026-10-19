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

    private void bindOpenKey() {
        AuthContext.set(new AuthProperties.KeyEntry("open-key", "acme", List.of("*"), List.of("open")));
    }

    private void bindAdminKey() {
        AuthContext.set(new AuthProperties.KeyEntry("admin-key", "acme", List.of("*"), List.of("open", "admin")));
    }

    @Test
    void adminScopeBypassesRbac() {
        bindAdminKey();
        guard.require("iam:admin");
        guard.require("billing:write");
    }

    @Test
    void openKeyNeedsIamPermission() {
        bindOpenKey();
        iam.assignRole("acme", "acme:open-****", IamService.ROLE_DEVELOPER);
        assertTrue(iam.hasPermission("acme", "acme:open-****", Set.of("developer"), "orch:run"));
        assertFalse(iam.hasPermission("acme", "acme:open-****", Set.of("developer"), "skill:review"));
    }

    @Test
    void openKeyWithoutRoleDenied() {
        bindOpenKey();
        assertThrows(UnauthorizedException.class, () -> guard.require("iam:admin"));
    }

    @Test
    void auditorBillingRead() {
        assertTrue(iam.hasPermission("acme", "bob", Set.of("auditor"), "billing:read"));
        assertFalse(iam.hasPermission("acme", "bob", Set.of("auditor"), "billing:write"));
        assertFalse(iam.hasPermission("acme", "bob", Set.of("auditor"), "orch:write"));
        assertFalse(iam.hasPermission("acme", "bob", Set.of("auditor"), "iam:admin"));
    }

    @Test
    void iamAdminRoleIsSuperuser() {
        assertTrue(iam.hasPermission("acme", "root", Set.of("admin"), "iam:admin"));
        assertTrue(iam.hasPermission("acme", "root", Set.of("ADMIN"), "billing:write"));
        assertTrue(iam.hasPermission("acme", "root", Set.of("superadmin"), "iam:admin"));
    }
}
