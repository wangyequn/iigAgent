package com.sinosoft.amp.openapi.ops;

import com.sinosoft.amp.openapi.security.AuthContext;

/**
 * 工具调用审计入口（HTTP / MCP）。写失败不阻断工具执行。
 * 由 {@link AuditLogService} 启动时绑定。
 */
public final class ToolCallAuditor {

    private static volatile AuditLogService service;

    private ToolCallAuditor() {
    }

    public static void bind(AuditLogService audit) {
        service = audit;
    }

    public static void record(String toolName, String action, String detail,
                              Integer statusCode, Long durationMs) {
        AuditLogService s = service;
        if (s == null || toolName == null) {
            return;
        }
        String tenant;
        try {
            tenant = AuthContext.requireTenantId();
        } catch (RuntimeException e) {
            tenant = "unknown";
        }
        try {
            s.record(tenant, AuthContext.actorLabel(), action, "TOOL", toolName,
                    "TOOL", toolName, truncate(detail), statusCode, durationMs);
        } catch (RuntimeException ignored) {
            // 审计失败不影响工具
        }
    }

    private static String truncate(String s) {
        if (s == null) {
            return null;
        }
        return s.length() > 800 ? s.substring(0, 800) + "..." : s;
    }
}
