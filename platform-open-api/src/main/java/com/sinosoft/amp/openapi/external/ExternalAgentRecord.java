package com.sinosoft.amp.openapi.external;

import java.time.Instant;

public record ExternalAgentRecord(
        String id,
        String tenantId,
        String name,
        String baseUrl,
        String callbackKey,
        String capabilities,
        String status,
        Instant lastHeartbeat,
        Instant createdAt
) {
    public static final String HEALTHY = "HEALTHY";
    public static final String UNHEALTHY = "UNHEALTHY";
}
