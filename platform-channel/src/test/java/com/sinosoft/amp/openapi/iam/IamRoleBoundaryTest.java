package com.sinosoft.amp.openapi.security;

import com.sinosoft.amp.openapi.iam.IamService;
import com.sinosoft.amp.openapi.iam.RbacGuard;
import com.sinosoft.amp.openapi.security.IamProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 认证授权补充：IAM 角色授权/回收、角色↔权限映射、trust-headers 边界、租户隔离。
 */
class IamRoleBoundaryTest {

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
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void unknownRoleRejected() {
        assertThrows(UnauthorizedException.class, () -> iam.assignRole("acme", "u1", "superhero"));
        assertThrows(UnauthorizedException.class, () -> iam.assignRole("acme", "u1", " "));
        assertThrows(UnauthorizedException.class, () -> iam.assignRole("acme", "u1", null));
    }

    @Test
    void revokeRoleRemovesPermission() {
        iam.assignRole("acme", "bob", IamService.ROLE_SKILL_REVIEWER);
        assertTrue(iam.hasPermission("acme", "bob", iam.rolesOf("acme", "bob"), "skill:review"));
        assertFalse(iam.hasPermission("acme", "bob", iam.rolesOf("acme", "bob"), "iam:admin"));

        iam.revokeRole("acme", "bob", IamService.ROLE_SKILL_REVIEWER);
        assertEquals(Set.of(), iam.rolesOf("acme", "bob"));
        // 无角色 → RBAC 默认 agent_caller，仅 agent:call
        Set<String> defaults = iam.permissionsOfRoles("acme", Set.of());
        assertTrue(defaults.isEmpty());
    }

    @Test
    void roleCaseInsensitive() {
        iam.assignRole("acme", "carol", "Tenant_Admin");
        assertTrue(iam.rolesOf("acme", "carol").contains("tenant_admin"));
        assertTrue(iam.hasPermission("acme", "carol", Set.of("TENANT_ADMIN"), "iam:admin"));
    }

    @Test
    void rolesAreTenantScoped() {
        iam.assignRole("acme", "dave", IamService.ROLE_AUDITOR);
        assertTrue(iam.rolesOf("acme", "dave").contains("auditor"));
        assertEquals(Set.of(), iam.rolesOf("other", "dave"));
    }

    @Test
    void auditorCannotWriteBilling() {
        iam.assignRole("acme", "eve", IamService.ROLE_AUDITOR);
        assertTrue(iam.hasPermission("acme", "eve", Set.of("auditor"), "billing:read"));
        assertFalse(iam.hasPermission("acme", "eve", Set.of("auditor"), "billing:write"));
        assertFalse(iam.hasPermission("acme", "eve", Set.of("auditor"), "orch:write"));
    }

    @Test
    void opsCanWriteButNotReviewSkills() {
        iam.assignRole("acme", "frank", IamService.ROLE_OPS);
        assertTrue(iam.hasPermission("acme", "frank", Set.of("ops"), "admin:write"));
        assertTrue(iam.hasPermission("acme", "frank", Set.of("ops"), "orch:run"));
        assertFalse(iam.hasPermission("acme", "frank", Set.of("ops"), "skill:review"));
        assertFalse(iam.hasPermission("acme", "frank", Set.of("ops"), "iam:admin"));
    }

    @Test
    void trustHeadersDisabledByDefault() {
        AuthContext.set(new AuthProperties.KeyEntry(
                "k", "acme", List.of("demo"), List.of("open")));
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader(RbacGuard.HEADER_ROLES, "tenant_admin");
        req.addHeader(RbacGuard.HEADER_USER, "mallory");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(req));

        // trust-headers=false：自报角色不生效，默认 agent_caller 被拒
        assertThrows(UnauthorizedException.class, () -> guard.require("iam:admin"));
    }

    @Test
    void trustHeadersEnabledOnlyWithFlag() {
        RbacGuard trusting = new RbacGuard(iam, new IamProperties("", "", true));
        AuthContext.set(new AuthProperties.KeyEntry(
                "k", "acme", List.of("demo"), List.of("open")));
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader(RbacGuard.HEADER_ROLES, "auditor");
        req.addHeader(RbacGuard.HEADER_USER, "grace");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(req));

        trusting.require("billing:read");
        assertThrows(UnauthorizedException.class, () -> trusting.require("billing:write"));
    }

    @Test
    void defaultRolesCatalogComplete() {
        var defaults = iam.describeDefaults();
        assertTrue(defaults.containsKey(IamService.ROLE_TENANT_ADMIN));
        assertTrue(defaults.containsKey(IamService.ROLE_AGENT_CALLER));
        assertEquals(6, defaults.size());
    }
}
