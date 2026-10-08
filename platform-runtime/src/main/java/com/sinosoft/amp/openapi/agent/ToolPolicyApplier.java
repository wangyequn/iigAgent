package com.sinosoft.amp.openapi.agent;

import io.agentscope.harness.agent.tools.ToolsConfig;

/**
 * 把 ToolPolicyProperties 映射为 Harness ToolsConfig（deny 或 allow 模式）。
 */
public final class ToolPolicyApplier {

    private final ToolPolicyProperties policy;

    public ToolPolicyApplier(ToolPolicyProperties policy) {
        this.policy = policy == null
                ? new ToolPolicyProperties(ToolPolicyProperties.MODE_DENY, null, null, false, false, false)
                : policy;
    }

    public ToolsConfig apply() {
        ToolsConfig config = new ToolsConfig();
        if (policy.allowlistMode()) {
            config.setAllow(policy.effectiveAllow());
            // allow 模式下仍把明确危险项列入 deny，双保险
            config.setDeny(policy.effectiveDeny().stream()
                    .filter(d -> !policy.effectiveAllow().contains(d))
                    .toList());
        } else {
            config.setDeny(policy.effectiveDeny());
        }
        return config;
    }

    public ToolPolicyProperties policy() {
        return policy;
    }
}
