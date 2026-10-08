package com.example.agent.runtime;

import io.agentscope.harness.agent.HarnessAgent;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 进程内 Agent 会话缓存：按 agentId 懒加载单例。
 * 中台版还有版本切流 / drain / 租户隔离；starter 用「每 agentId 一实例」足够。
 */
public class AgentSessions implements AutoCloseable {

    private final HarnessFactory factory;
    private final Map<String, HarnessAgent> agents = new ConcurrentHashMap<>();

    public AgentSessions(HarnessFactory factory) {
        this.factory = factory;
    }

    public HarnessAgent get(String agentId) {
        return agents.computeIfAbsent(agentId, id -> factory.build(id, null));
    }

    public HarnessAgent get(String agentId, String sysPrompt) {
        HarnessAgent existing = agents.get(agentId);
        if (existing != null) {
            return existing;
        }
        return agents.computeIfAbsent(agentId, id -> factory.build(id, sysPrompt));
    }

    @Override
    public void close() {
        agents.forEach((id, agent) -> {
            try {
                agent.close();
            } catch (Exception e) {
                // ignore
            }
        });
        agents.clear();
    }
}
