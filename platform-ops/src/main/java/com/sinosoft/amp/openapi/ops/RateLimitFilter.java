package com.sinosoft.amp.openapi.ops;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * S7 限流中间件（进程内固定窗口）。/health 与 /actuator 不限流。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 5)
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    private final RuntimeConfigService config;

    public RateLimitFilter(RuntimeConfigService config) {
        this.config = config;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.startsWith("/api/v1/health") || path.startsWith("/actuator");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        String key = request.getHeader("Authorization");
        if (key == null || key.isBlank()) {
            key = request.getRemoteAddr();
        } else if (key.startsWith("Bearer ")) {
            key = key.substring(7).trim();
        }
        if (!config.tryAcquire(key)) {
            log.warn("rate limit exceeded key={} path={}", mask(key), request.getRequestURI());
            response.setStatus(429);
            response.setContentType("application/json;charset=UTF-8");
            response.setHeader("Retry-After", "60");
            response.getWriter().write(
                    "{\"code\":\"RATE_LIMITED\",\"message\":\"too many requests, retry after 60s\"}");
            return;
        }
        filterChain.doFilter(request, response);
    }

    private static String mask(String key) {
        if (key == null) {
            return "-";
        }
        return key.length() <= 4 ? "****" : key.substring(0, 2) + "****";
    }
}
