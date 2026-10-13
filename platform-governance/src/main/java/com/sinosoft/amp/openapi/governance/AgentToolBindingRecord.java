package com.sinosoft.amp.openapi.governance;

/**
 * Agent × 工具装配：存在绑定行时仅 enabled=true 的工具挂到该 Agent Toolkit。
 */
public record AgentToolBindingRecord(
        String tenantId,
        String agentId,
        String toolName,
        boolean enabled,
        String policyOverride
) {
}
