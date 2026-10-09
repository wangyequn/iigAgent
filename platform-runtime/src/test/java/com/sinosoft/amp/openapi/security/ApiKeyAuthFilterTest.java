package com.sinosoft.amp.openapi.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApiKeyAuthFilterTest {

    private final AuthProperties props = new AuthProperties(true, List.of(
            new AuthProperties.KeyEntry("test-key", "acme", List.of("demo")),
            new AuthProperties.KeyEntry("open-key", "acme", List.of("demo"), List.of("open")),
            new AuthProperties.KeyEntry("admin-key", "acme", List.of("demo"), List.of("admin"))
    ));
    private final ApiKeyAuthFilter filter = new ApiKeyAuthFilter(props);

    @AfterEach
    void cleanup() {
        AuthContext.clear();
    }

    @Test
    void rejectsMissingKey() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/agents/demo/messages");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        assertEquals(401, response.getStatus());
        assertTrue(response.getContentAsString().contains("MISSING_API_KEY"));
        assertNull(AuthContext.get());
    }

    @Test
    void rejectsUnknownKey() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/agents/demo/messages");
        request.addHeader("Authorization", "Bearer wrong");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        assertEquals(401, response.getStatus());
    }

    @Test
    void acceptsValidKeyAndClearsContext() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/agents/demo/messages");
        request.addHeader("Authorization", "Bearer test-key");
        MockHttpServletResponse response = new MockHttpServletResponse();
        final boolean[] sawTenant = {false};
        MockFilterChain chain = new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res) {
                sawTenant[0] = "acme".equals(AuthContext.requireTenantId());
            }
        };
        filter.doFilter(request, response, chain);
        assertEquals(200, response.getStatus());
        assertTrue(sawTenant[0]);
        assertNull(AuthContext.get());
    }

    @Test
    void skipsHealth() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/health/demo");
        assertTrue(filter.shouldNotFilter(request));
    }

    @Test
    void openKeyCannotCallAdmin() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/admin/skills");
        request.addHeader("Authorization", "Bearer open-key");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        assertEquals(403, response.getStatus());
        assertTrue(response.getContentAsString().contains("INSUFFICIENT_SCOPE"));
    }

    @Test
    void adminKeyCannotCallOpenWhenScopedAdminOnly() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/agents/demo/messages");
        request.addHeader("Authorization", "Bearer admin-key");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        assertEquals(403, response.getStatus());
    }

    @Test
    void adminPathDetection() {
        assertTrue(ApiKeyAuthFilter.isAdminPath("/api/v1/admin/skills"));
        assertFalse(ApiKeyAuthFilter.isAdminPath("/api/v1/agents/demo/messages"));
    }

    @Test
    void unknownBearerPassesThroughWhenJwtEnabled() throws Exception {
        ApiKeyAuthFilter jwtFilter = new ApiKeyAuthFilter(
                props, new IamProperties("https://idp.example/realms/amp", "", false));
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/agents/demo/messages");
        request.addHeader("Authorization", "Bearer some.jwt.token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        final boolean[] continued = {false};
        MockFilterChain chain = new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res) {
                continued[0] = true;
            }
        };
        jwtFilter.doFilter(request, response, chain);
        assertTrue(continued[0]);
        assertNull(AuthContext.get());
        assertEquals(200, response.getStatus());
    }

    @Test
    void knownKeyStillPreferredWhenJwtEnabled() throws Exception {
        ApiKeyAuthFilter jwtFilter = new ApiKeyAuthFilter(
                props, new IamProperties("https://idp.example/realms/amp", "", false));
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/agents/demo/messages");
        request.addHeader("Authorization", "Bearer test-key");
        MockHttpServletResponse response = new MockHttpServletResponse();
        final String[] tenant = {null};
        MockFilterChain chain = new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res) {
                tenant[0] = AuthContext.requireTenantId();
            }
        };
        jwtFilter.doFilter(request, response, chain);
        assertEquals("acme", tenant[0]);
    }
}
