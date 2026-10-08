package com.example.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 最小配置：模型、系统提示词、工作区、Redis 状态。
 * 对齐中台 amp.agent.* 的常用子集，刻意不带治理/技能/知识等平台项。
 */
@ConfigurationProperties(prefix = "amp.agent")
public record AgentProperties(
        String name,
        String modelId,
        String sysPrompt,
        String workspace,
        String redisUri,
        String stateKeyPrefix,
        Integer compactionTriggerMessages,
        Integer compactionKeepMessages
) {
    public String effectiveModelId() {
        return modelId == null || modelId.isBlank() ? "deepseek:deepseek-flash" : modelId;
    }

    public String effectiveName() {
        return name == null || name.isBlank() ? "demo" : name;
    }

    public String effectiveWorkspace() {
        return workspace == null || workspace.isBlank() ? "./data/workspace" : workspace;
    }

    public String effectiveStateKeyPrefix() {
        return stateKeyPrefix == null || stateKeyPrefix.isBlank() ? "amp:starter:state:" : stateKeyPrefix;
    }

    public int triggerMessages() {
        return compactionTriggerMessages == null ? 30 : compactionTriggerMessages;
    }

    public int keepMessages() {
        return compactionKeepMessages == null ? 10 : compactionKeepMessages;
    }
}
