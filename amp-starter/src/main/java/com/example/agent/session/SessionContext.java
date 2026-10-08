package com.example.agent.session;

/**
 * sessionId / userId / tenantId 校验（对齐中台 SessionContext）。
 * 规则：sessionId 必须 [A-Za-z0-9_-]{1,128}，三者均非空。
 */
public final class SessionContext {

    private SessionContext() {
    }

    public record SessionKey(String tenantId, String userId, String sessionId, String scopedUserId) {
    }

    public static SessionKey require(String tenantId, String userId, String sessionId) {
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
        // Windows 路径不允许 ':'，租户隔离用 '_'
        String scopedUser = tenantId.contains("_") || tenantId.contains(":")
                ? userId
                : tenantId + "_" + userId;
        return new SessionKey(tenantId, userId, sessionId, scopedUser);
    }

    /** 转成 AgentScope RuntimeContext（Harness 寻址用）。 */
    public static io.agentscope.core.agent.RuntimeContext toRuntimeContext(SessionKey key) {
        return io.agentscope.core.agent.RuntimeContext.builder()
                .userId(key.scopedUserId())
                .sessionId(key.sessionId())
                .build();
    }
}
