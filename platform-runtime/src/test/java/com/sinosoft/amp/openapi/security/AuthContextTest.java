package com.sinosoft.amp.openapi.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AuthContextTest {

    @AfterEach
    void tearDown() {
        AuthContext.clear();
    }

    @Test
    void requiresTenant() {
        assertThrows(UnauthorizedException.class, AuthContext::requireTenantId);
    }

    @Test
    void bindsTenantFromKey() {
        AuthContext.set(new AuthProperties.KeyEntry("k", "acme", List.of("demo")));
        assertEquals("acme", AuthContext.requireTenantId());
        assertDoesNotThrow(() -> AuthContext.requireAgent("demo"));
        assertThrows(UnauthorizedException.class, () -> AuthContext.requireAgent("other"));
    }
}
