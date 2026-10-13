package com.sinosoft.amp.openapi.catalog;

import java.time.Instant;

public record AgentRecord(
        String tenantId,
        String agentId,
        String name,
        AgentStatus status,
        Instant createdAt,
        String systemCode
) {
    public AgentRecord(String tenantId, String agentId, String name, AgentStatus status, Instant createdAt) {
        this(tenantId, agentId, name, status, createdAt, null);
    }
}
