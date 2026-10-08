package com.sinosoft.amp.openapi.catalog;

import java.time.Instant;

public record EndpointRelease(
        String tenantId,
        String agentId,
        int releaseVersion,
        Instant updatedAt
) {
}
