package com.sinosoft.amp.openapi.ops;

import com.sinosoft.amp.openapi.ops.RuntimeConfigService;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.io.UnsupportedEncodingException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * API 网关限流测试：固定窗口、按 Key 隔离、429 + Retry-After、health 豁免。
 */
class RateLimitFilterTest {

    private RuntimeConfigService config;
    private RateLimitFilter filter;

    @BeforeEach
    void setUp() {
        config = new RuntimeConfigService(new com.sinosoft.amp.openapi.ops.OpsProperties(true, 2, 3, 2));
        filter = new RateLimitFilter(config);
    }

    @AfterEach
    void tearDown() {
        config.update(java.util.Map.of("rateLimitEnabled", Boolean.TRUE));
    }

    private MockHttpServletRequest request(String path, String bearer) {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", path);
        req.setRequestURI(path);
        if (bearer != null) {
            req.addHeader("Authorization", "Bearer " + bearer);
        }
        return req;
    }

    @Test
    void rejectsFourthRequestWith429() throws ServletException, IOException {
        MockHttpServletResponse res1 = new MockHttpServletResponse();
        filter.doFilter(request("/api/v1/agents/demo/messages", "k-a"), new MockHttpServletResponse(), new MockFilterChain());
        filter.doFilter(request("/api/v1/agents/demo/messages", "k-a"), new MockHttpServletResponse(), new MockFilterChain());
        filter.doFilter(request("/api/v1/agents/demo/messages", "k-a"), res1, new MockFilterChain());
        // 前 2 次放行（limit=2），但本 filter 每次都计数；第 3 次已被拒绝
        assertEquals(429, res1.getStatus());
        assertTrue(res1.getContentAsString().contains("RATE_LIMITED"));
        assertEquals("60", res1.getHeader("Retry-After"));
    }

    @Test
    void limitsArePerKey() throws ServletException, IOException {
        filter.doFilter(request("/api/v1/agents/demo/messages", "k-a"), new MockHttpServletResponse(), new MockFilterChain());
        filter.doFilter(request("/api/v1/agents/demo/messages", "k-a"), new MockHttpServletResponse(), new MockFilterChain());
        // 另一个 key 不受影响
        MockHttpServletResponse resB = new MockHttpServletResponse();
        filter.doFilter(request("/api/v1/agents/demo/messages", "k-b"), resB, new MockFilterChain());
        assertEquals(200, resB.getStatus());
    }

    @Test
    void healthIsExempt() throws ServletException, IOException {
        for (int i = 0; i < 10; i++) {
            MockHttpServletResponse res = new MockHttpServletResponse();
            filter.doFilter(request("/api/v1/health/demo", null), res, new MockFilterChain());
            assertEquals(200, res.getStatus());
        }
    }

    @Test
    void disabledLimiterPassesAll() throws ServletException, IOException {
        config.update(java.util.Map.of("rateLimitEnabled", Boolean.FALSE));
        for (int i = 0; i < 10; i++) {
            MockHttpServletResponse res = new MockHttpServletResponse();
            filter.doFilter(request("/api/v1/agents/demo/messages", "k-a"), res, new MockFilterChain());
            assertEquals(200, res.getStatus());
        }
    }

    @Test
    void fallbackKeysByRemoteAddr() throws ServletException, IOException {
        MockHttpServletRequest req = request("/api/v1/agents/demo/messages", null);
        req.setRemoteAddr("10.0.0.9");
        filter.doFilter(req, new MockHttpServletResponse(), new MockFilterChain());
        filter.doFilter(req, new MockHttpServletResponse(), new MockFilterChain());
        MockHttpServletResponse res = new MockHttpServletResponse();
        filter.doFilter(req, res, new MockFilterChain());
        assertEquals(429, res.getStatus());
        assertTrue(config.rejectedTotal() >= 1);
    }

    @Test
    void bearerPrefixStrippedForWindowKey() throws ServletException, IOException {
        // 带 Bearer 前缀与裸 key 命中同一窗口
        MockHttpServletRequest raw = request("/api/v1/agents/demo/messages", null);
        raw.addHeader("Authorization", "tok-1");
        MockHttpServletRequest bearer = request("/api/v1/agents/demo/messages", null);
        bearer.addHeader("Authorization", "Bearer tok-1");
        filter.doFilter(raw, new MockHttpServletResponse(), new MockFilterChain());
        filter.doFilter(bearer, new MockHttpServletResponse(), new MockFilterChain());
        MockHttpServletResponse res = new MockHttpServletResponse();
        filter.doFilter(bearer, res, new MockFilterChain());
        assertEquals(429, res.getStatus());
    }
}
