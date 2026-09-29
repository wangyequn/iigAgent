package com.sinosoft.amp.openapi.governance;

public record McpServerRecord(
        String tenantId,
        String name,
        String transport,
        String command,
        String args,
        String url,
        String enableTools,
        String status
) {
    public static final String STATUS_REGISTERED = "REGISTERED";
}
