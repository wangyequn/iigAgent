package com.sinosoft.amp.openapi.governance;

public record PermissionRuleRecord(
        String tenantId,
        String agentId,
        String toolName,
        String ruleContent,
        String behavior,
        String source
) {
    public static final String ALLOW = "ALLOW";
    public static final String ASK = "ASK";
    public static final String DENY = "DENY";
}
