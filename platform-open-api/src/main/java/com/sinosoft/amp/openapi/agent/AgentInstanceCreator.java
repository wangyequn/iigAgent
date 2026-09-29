package com.sinosoft.amp.openapi.agent;

import io.agentscope.harness.agent.HarnessAgent;

/**
 * 可替换的实例创建器，便于单测注入假 Agent。
 */
public interface AgentInstanceCreator {

    HarnessAgent create(String tenantId, String agentId, int version, String modelId, String sysPrompt, String workspace);
}
