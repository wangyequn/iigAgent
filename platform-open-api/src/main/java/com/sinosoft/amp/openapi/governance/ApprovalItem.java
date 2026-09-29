package com.sinosoft.amp.openapi.governance;

import java.time.Instant;

public record ApprovalItem(
        String id,
        String tenantId,
        String agentId,
        String userId,
        String sessionId,
        String replyId,
        String toolCallId,
        String toolName,
        String toolInput,
        String status,
        String decisionNote,
        String idempotencyKey,
        Instant createdAt,
        Instant resolvedAt
) {
    public static final String PENDING = "PENDING";
    public static final String APPROVED = "APPROVED";
    public static final String REJECTED = "REJECTED";
    public static final String EXPIRED = "EXPIRED";
}
