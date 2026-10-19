package com.sinosoft.amp.openapi.iam;

import com.sinosoft.amp.openapi.security.UnauthorizedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IamRoleBoundaryTest {

    private IamService iam;

    @BeforeEach
    void setUp() {
        iam = new IamService();
    }

    @Test
    void rejectsInvalidRole() {
        assertThrows(UnauthorizedException.class, () -> iam.assignRole("acme", "u1", "superhero"));
        assertThrows(UnauthorizedException.class, () -> iam.assignRole("acme", "u1", " "));
        assertThrows(UnauthorizedException.class, () -> iam.assignRole("acme", "u1", null));
    }

    @Test
    void skillReviewerPermissions() {
        iam.assignRole("acme", "bob", IamService.ROLE_SKILL_REVIEWER);
        assertTrue(iam.hasPermission("acme", "bob", iam.rolesOf("acme", "bob"), "skill:review"));
        assertFalse(iam.hasPermission("acme", "bob", iam.rolesOf("acme", "bob"), "iam:admin"));
        iam.revokeRole("acme", "bob", IamService.ROLE_SKILL_REVIEWER);
        assertEquals(Set.of(), iam.rolesOf("acme", "bob"));
    }

    @Test
    void tenantIsolation() {
        iam.assignRole("acme", "eve", IamService.ROLE_AUDITOR);
        assertTrue(iam.rolesOf("acme", "eve").contains("auditor"));
        assertEquals(Set.of(), iam.rolesOf("other", "eve"));
    }

    @Test
    void opsRolePermissions() {
        iam.assignRole("acme", "frank", IamService.ROLE_OPS);
        assertTrue(iam.hasPermission("acme", "frank", Set.of("ops"), "admin:write"));
        assertTrue(iam.hasPermission("acme", "frank", Set.of("ops"), "orch:run"));
        assertFalse(iam.hasPermission("acme", "frank", Set.of("ops"), "skill:review"));
        assertFalse(iam.hasPermission("acme", "frank", Set.of("ops"), "iam:admin"));
    }
}
