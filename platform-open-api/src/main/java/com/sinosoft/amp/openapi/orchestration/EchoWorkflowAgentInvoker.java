package com.sinosoft.amp.openapi.orchestration;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

/**
 * 占位 Invoker：无 RuntimeWorkflowAgentInvoker 时兜底（单测 / 极简环境）。
 * 生产由 RuntimeWorkflowAgentInvoker（@Primary）接管。
 */
@Component
@ConditionalOnMissingBean(name = "runtimeWorkflowAgentInvoker")
public class EchoWorkflowAgentInvoker implements WorkflowAgentInvoker {

    @Override
    public AgentResult invoke(String tenantId, String agentId, String userId, String sessionId, String input) {
        String text = "[agent:" + agentId + "] acknowledged: " + (input == null ? "" : input);
        int prompt = input == null ? 0 : Math.max(1, input.length() / 4);
        return new AgentResult(text, prompt, 8);
    }
}
