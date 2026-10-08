package com.sinosoft.amp.openapi.ops;

import com.sinosoft.amp.openapi.security.AuthContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 管理面写操作 / HITL confirm / interrupt 自动审计。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 30)
public class AuditFilter extends OncePerRequestFilter {

    private final AuditLogService auditLogService;

    public AuditFilter(AuditLogService auditLogService) {
        this.auditLogService = auditLogService;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !shouldAudit(request.getMethod(), request.getRequestURI());
    }

    static boolean shouldAudit(String method, String path) {
        if (path.startsWith("/api/v1/health") || path.startsWith("/actuator")) {
            return false;
        }
        boolean mutating = "POST".equalsIgnoreCase(method)
                || "PUT".equalsIgnoreCase(method)
                || "PATCH".equalsIgnoreCase(method)
                || "DELETE".equalsIgnoreCase(method);
        if (!mutating) {
            return false;
        }
        return path.startsWith("/api/v1/admin")
                || path.contains("/confirm")
                || path.contains("/interrupt")
                || path.endsWith("/publish")
                || path.contains(":publish");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        long start = System.currentTimeMillis();
        try {
            filterChain.doFilter(request, response);
        } finally {
            long duration = System.currentTimeMillis() - start;
            String path = request.getRequestURI();
            String action = resolveAction(request.getMethod(), path);
            auditLogService.record(
                    safeTenant(),
                    AuthContext.actorLabel(),
                    action,
                    "http",
                    path,
                    request.getMethod(),
                    path,
                    null,
                    response.getStatus(),
                    duration);
        }
    }

    private static String safeTenant() {
        try {
            return AuthContext.requireTenantId();
        } catch (Exception e) {
            return "";
        }
    }

    static String resolveAction(String method, String path) {
        if (path.contains("/confirm")) {
            return "HITL_CONFIRM";
        }
        if (path.contains("/interrupt")) {
            return "SESSION_INTERRUPT";
        }
        if (path.contains("publish")) {
            return "VERSION_PUBLISH";
        }
        if (path.contains("/skills/") && path.contains("approve")) {
            return "SKILL_APPROVE";
        }
        if (path.contains("/skills/") && path.contains("reject")) {
            return "SKILL_REJECT";
        }
        if (path.contains("/skills/") && path.contains("archive")) {
            return "SKILL_ARCHIVE";
        }
        if (path.contains("/approvals/") && path.contains("decide")) {
            return "HITL_DECIDE";
        }
        if (path.endsWith("/config")) {
            return "CONFIG_UPDATE";
        }
        if (path.contains("/permissions") || path.contains("permission-mode")) {
            return "PERMISSION_CHANGE";
        }
        if (path.contains("/mcp-servers")) {
            return "MCP_REGISTER";
        }
        if (path.contains("/external-agents")) {
            return "EXTERNAL_REGISTER";
        }
        if (path.contains("/knowledge")) {
            return "KNOWLEDGE_WRITE";
        }
        return "ADMIN_" + (method == null ? "POST" : method.toUpperCase());
    }
}
