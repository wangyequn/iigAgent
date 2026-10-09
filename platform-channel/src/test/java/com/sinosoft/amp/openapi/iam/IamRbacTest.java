package com.sinosoft.amp.openapi.iam;

import com.sinosoft.amp.openapi.security.AuthContext;
import com.sinosoft.amp.openapi.security.AuthProperties;
import com.sinosoft.amp.openapi.security.IamProperties;
import com.sinosoft.amp.openapi.security.JwtAuthSupport;
import com.sinosoft.amp.openapi.security.UnauthorizedException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
        JwtAuthSupport.clear();
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void agentCallerDeniedOrchestrationWrite() {
        AuthContext.set(new AuthProperties.KeyEntry("k", "acme", List.of("demo"), List.of("open")));
        // 无角色 → 默认 agent_caller
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

    @Test
    void headersIgnoredByDefault() {
        AuthContext.set(new AuthProperties.KeyEntry("k", "acme", List.of("demo"), List.of("open")));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(RbacGuard.HEADER_USER, "mallory");
        request.addHeader(RbacGuard.HEADER_ROLES, "tenant_admin");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        assertEquals("acme:****", guard.currentUserId());
        assertFalse(guard.currentRoles("acme", "k").contains("tenant_admin"));
        assertThrows(UnauthorizedException.class, () -> guard.require("iam:admin"));
    }

    @Test
    void headersTrustedWhenMigrationFlagOn() {
        RbacGuard trusting = new RbacGuard(iam, new IamProperties("", "", true));
        AuthContext.set(new AuthProperties.KeyEntry("k", "acme", List.of("demo"), List.of("open")));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(RbacGuard.HEADER_USER, "alice");
        request.addHeader(RbacGuard.HEADER_ROLES, "developer");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        assertEquals("alice", trusting.currentUserId());
        assertEquals(Set.of("developer"), trusting.currentRoles("acme", "alice"));
        trusting.require("orch:write");
    }

    @Test
    void jwtRoleDeveloperCanWriteButNotReviewSkills() {
        AuthContext.set(new AuthProperties.KeyEntry("k", "acme", List.of("demo"), List.of("open")));
        Jwt jwt = Jwt.withTokenValue("tok").header("alg", "RS256").subject("alice")
                .claim("tenant", "acme")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(3600))
                .build();
        JwtAuthSupport.setAuthenticationForTest(new JwtAuthenticationToken(jwt,
                List.of(new SimpleGrantedAuthority("ROLE_developer"))));

        assertEquals("alice", guard.currentUserId());
        guard.require("orch:run");
        assertThrows(UnauthorizedException.class, () -> guard.require("skill:review"));
    }

    @Test
    void jwtTenantAdminPassesAll() {
        Jwt jwt = Jwt.withTokenValue("tok").header("alg", "RS256").subject("root")
                .claim("tenant", "acme")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(3600))
                .build();
        JwtAuthSupport.setAuthenticationForTest(new JwtAuthenticationToken(jwt,
                List.of(new SimpleGrantedAuthority("ROLE_tenant_admin"))));
        guard.require("iam:admin");
        guard.require("billing:write");
    }

    @Test
    void jwtRolesWinOverHeaderSpoofEvenIfTrusted() {
        RbacGuard trusting = new RbacGuard(iam, new IamProperties("", "", true));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(RbacGuard.HEADER_ROLES, "tenant_admin");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        Jwt jwt = Jwt.withTokenValue("tok").header("alg", "RS256").subject("bob")
                .claim("tenant", "acme")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(3600))
                .build();
        JwtAuthSupport.setAuthenticationForTest(new JwtAuthenticationToken(jwt,
                List.of(new SimpleGrantedAuthority("ROLE_agent_caller"))));

        assertEquals(Set.of("agent_caller"), trusting.currentRoles("acme", "bob"));
        assertThrows(UnauthorizedException.class, () -> trusting.require("iam:admin"));
    }
}
