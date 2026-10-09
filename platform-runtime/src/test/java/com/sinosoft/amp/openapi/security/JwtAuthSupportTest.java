package com.sinosoft.amp.openapi.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JwtAuthSupportTest {

    @AfterEach
    void cleanup() {
        JwtAuthSupport.clear();
        AuthContext.clear();
    }

    @Test
    void extractsSubTenantAndRoles() {
        Jwt jwt = jwtBuilder()
                .claim("tenant", "acme")
                .claim("roles", List.of("developer", "auditor"))
                .build();
        JwtAuthenticationToken token = new JwtAuthenticationToken(jwt,
                List.of(new SimpleGrantedAuthority("ROLE_developer")));
        JwtAuthSupport.setAuthenticationForTest(token);

        JwtIdentity identity = JwtAuthSupport.current();
        assertEquals("alice", identity.userId());
        assertEquals("acme", identity.tenantId());
        assertTrue(identity.roles().contains("developer"));
        assertTrue(identity.roles().contains("auditor"));
    }

    @Test
    void readsKeycloakNestedRolesAndAzpTenant() {
        Jwt jwt = jwtBuilder()
                .claim("azp", "amp-console")
                .claim("realm_access", Map.of("roles", List.of("tenant_admin")))
                .claim("resource_access", Map.of("amp", Map.of("roles", List.of("ops"))))
                .build();
        JwtIdentity identity = JwtAuthSupport.fromJwt(jwt, List.of());
        assertEquals("amp-console", identity.tenantId());
        assertTrue(identity.roles().contains("tenant_admin"));
        assertTrue(identity.roles().contains("ops"));
    }

    @Test
    void requireTenantIdPrefersJwtOverKey() {
        AuthContext.set(new AuthProperties.KeyEntry("k", "from-key", List.of("demo"), List.of("open")));
        Jwt jwt = jwtBuilder().claim("tenant", "from-jwt").build();
        JwtAuthSupport.setAuthenticationForTest(new JwtAuthenticationToken(jwt, List.of()));
        assertEquals("from-jwt", AuthContext.requireTenantId());
        assertEquals("alice", AuthContext.actorLabel());
    }

    @Test
    void jwtAdminRolePassesRequireAdmin() {
        Jwt jwt = jwtBuilder().claim("tenant", "acme").build();
        JwtAuthSupport.setAuthenticationForTest(new JwtAuthenticationToken(jwt,
                List.of(new SimpleGrantedAuthority("ROLE_tenant_admin"))));
        AuthContext.requireAdmin();
    }

    @Test
    void jwtWithoutAdminRoleRejectedByRequireAdmin() {
        Jwt jwt = jwtBuilder().claim("tenant", "acme").build();
        JwtAuthSupport.setAuthenticationForTest(new JwtAuthenticationToken(jwt,
                List.of(new SimpleGrantedAuthority("ROLE_developer"))));
        org.junit.jupiter.api.Assertions.assertThrows(UnauthorizedException.class, AuthContext::requireAdmin);
    }

    @Test
    void currentIsNullWithoutJwt() {
        assertNull(JwtAuthSupport.current());
    }

    private static Jwt.Builder jwtBuilder() {
        return Jwt.withTokenValue("tok")
                .header("alg", "RS256")
                .subject("alice")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600));
    }
}
