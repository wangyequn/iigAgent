package com.sinosoft.amp.openapi.orchestration;

/**
 * Workflow 内调用托管 Agent 的抽象。
 * S9 默认实现可返回占位结果；后续可接到 AgentInstance / ExternalChat。
 */
public interface WorkflowAgentInvoker {

    record AgentResult(String text, int promptTokens, int completionTokens) {
    }

    AgentResult invoke(String tenantId, String agentId, String userId, String sessionId, String input);
}
