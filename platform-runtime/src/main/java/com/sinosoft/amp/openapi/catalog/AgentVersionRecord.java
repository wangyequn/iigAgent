package com.sinosoft.amp.openapi.catalog;

import java.time.Instant;

public record AgentVersionRecord(
        String tenantId,
        String agentId,
        int version,
        VersionStatus status,
        String modelId,
        String sysPrompt,
        String workspace,
        Instant createdAt,
        Instant publishedAt
) {
}
