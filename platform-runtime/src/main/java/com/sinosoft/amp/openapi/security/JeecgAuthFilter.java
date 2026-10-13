package com.sinosoft.amp.openapi.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Optional;

/**
 * 认 pc_platform 的 {@code X-Access-Token}：loginByToken + 权限码。
 * 与 API Key 双轨：有 Jeecg 票则人轨优先，机器 Key 仍可单独使用。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 8)
public class JeecgAuthFilter extends OncePerRequestFilter {

    private final JeecgAuthProperties properties;
    private final JeecgAuthClient client;

    @Autowired
    public JeecgAuthFilter(JeecgAuthProperties properties, JeecgAuthClient client) {
        this.properties = properties;
        this.client = client;
    }

    public JeecgAuthFilter(JeecgAuthProperties properties) {
        this(properties, new JeecgAuthClient(properties));
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!properties.enabled()) {
            return true;
        }
        String path = request.getRequestURI();
        return path == null
                || path.startsWith("/api/v1/health")
                || path.startsWith("/api/v1/auth/jeecg/config")
                || path.startsWith("/api/v1/auth/jeecg/exchange")
                || path.startsWith("/api/v1/auth/jeecg/login")
                || !(path.startsWith("/api/") || path.startsWith("/v1/chat/"));
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        String token = firstNonBlank(request.getHeader(properties.tokenHeader()),
                request.getHeader(JeecgAuthProperties.DEFAULT_HEADER));
        if (token == null) {
            filterChain.doFilter(request, response);
            return;
        }
        try {
            Optional<JeecgIdentity> identity = client.authenticate(token.trim());
            if (identity.isEmpty()) {
                reject(response, 401, "INVALID_JEECG_TOKEN", "jeecg token invalid or expired");
                return;
            }
            AuthContext.setJeecg(identity.get());
            AuthContext.setUserToken(token.trim());
            UserTokenHolder.bind(identity.get().username(), token.trim());
            filterChain.doFilter(request, response);
        } catch (IllegalStateException e) {
            reject(response, 503, "JEECG_AUTH_MISCONFIGURED", e.getMessage());
        } catch (Exception e) {
            reject(response, 401, "INVALID_JEECG_TOKEN", "jeecg auth failed: " + e.getMessage());
        } finally {
            AuthContext.clearJeecg();
        }
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) {
            return a;
        }
        return b != null && !b.isBlank() ? b : null;
    }

    private void reject(HttpServletResponse response, int status, String code, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":\"" + code + "\",\"message\":\"" + message + "\"}");
    }
}
