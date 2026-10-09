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
import java.util.Map;

/**
 * Bearer API Key → 线程上下文租户；/health 放行。
 * /api/v1/admin/** 需要 admin scope，其余 /api/** 与 OpenAI 兼容 /v1/chat/** 需要 open scope。
 *
 * <p>双轨：未知 Bearer 在 {@code amp.iam.issuer-uri} 启用时放行给 JWT Resource Server 校验。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class ApiKeyAuthFilter extends OncePerRequestFilter {

    public static final String HEADER = "Authorization";
    public static final String PREFIX = "Bearer ";

    private final AuthProperties properties;
    private final Map<String, AuthProperties.KeyEntry> keys;
    private final boolean jwtEnabled;

    public ApiKeyAuthFilter(AuthProperties properties) {
        this(properties, new IamProperties("", "", false));
    }

    @Autowired
    public ApiKeyAuthFilter(AuthProperties properties, IamProperties iamProperties) {
        this.properties = properties;
        this.keys = properties.byKey();
        this.jwtEnabled = iamProperties != null && iamProperties.jwtEnabled();
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        boolean covered = path.startsWith("/api/") || path.startsWith("/v1/chat/");
        // Channel Webhook 走签名/Token，不走 API Key
        boolean channelWebhook = path.startsWith("/api/v1/channels/") && path.endsWith("/webhook");
        boolean automationWebhook = path.startsWith("/api/v1/automations/") && path.endsWith("/webhook");
        return !covered
                || path.startsWith("/api/v1/health")
                || channelWebhook
                || automationWebhook
                || !properties.enabled();
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        try {
            String header = request.getHeader(HEADER);
            if (header == null || !header.startsWith(PREFIX)) {
                reject(response, 401, "MISSING_API_KEY", "Authorization: Bearer <api-key> required");
                return;
            }
            String raw = header.substring(PREFIX.length()).trim();
            AuthProperties.KeyEntry entry = keys.get(raw);
            if (entry == null) {
                // 人轨 JWT：交给 OAuth2 Resource Server；非 JWT 会由其返回 INVALID_TOKEN
                if (jwtEnabled) {
                    filterChain.doFilter(request, response);
                    return;
                }
                reject(response, 401, "INVALID_API_KEY", "unknown api key");
                return;
            }
            String path = request.getRequestURI();
            String required = isAdminPath(path) ? AuthProperties.SCOPE_ADMIN : AuthProperties.SCOPE_OPEN;
            if (!entry.allowsScope(required)) {
                reject(response, 403, "INSUFFICIENT_SCOPE",
                        "api key missing scope: " + required + " (admin APIs require scope=admin)");
                return;
            }
            AuthContext.set(entry);
            filterChain.doFilter(request, response);
        } finally {
            AuthContext.clear();
        }
    }

    static boolean isAdminPath(String path) {
        return path.startsWith("/api/v1/admin");
    }

    private void reject(HttpServletResponse response, int status, String code, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(
                "{\"code\":\"" + code + "\",\"message\":\"" + message + "\"}");
    }
}
