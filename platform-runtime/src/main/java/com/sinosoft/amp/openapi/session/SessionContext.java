package com.sinosoft.amp.openapi.session;

import io.agentscope.core.agent.RuntimeContext;

public final class SessionContext {

    private SessionContext() {
    }

    /**
     * 强制 userId / sessionId（设计 4.1.2）：内部作用域 tenant_endUser，缺省拒绝。
     */
    public static RuntimeContext require(String tenantId, String userId, String sessionId) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new SessionContextException("tenantId is required");
        }
        if (userId == null || userId.isBlank()) {
            throw new SessionContextException("userId is required");
        }
        if (sessionId == null || sessionId.isBlank()) {
            throw new SessionContextException("sessionId is required");
        }
        if (sessionId.length() > 128 || !sessionId.matches("[A-Za-z0-9_-]+")) {
            throw new SessionContextException("sessionId must match [A-Za-z0-9_-]{1,128}");
        }
        // Windows 路径不允许 ':'，租户隔离用 '_'，与端到端 userId 规则一致
        String scopedUser = tenantId.contains("_") || tenantId.contains(":")
                ? userId
                : tenantId + "_" + userId;
        return RuntimeContext.builder()
                .userId(scopedUser)
                .sessionId(sessionId)
                .build();
    }
}
