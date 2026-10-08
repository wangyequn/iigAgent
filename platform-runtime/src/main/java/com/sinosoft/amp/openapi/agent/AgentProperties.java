package com.sinosoft.amp.openapi.agent;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "amp.agent")
public record AgentProperties(
        String name,
        String modelId,
        String sysPrompt,
        String workspace,
        String redisUri,
        String stateKeyPrefix,
        boolean requireSessionContext,
        // 以下为 AgentScope 运行时参数（null/0 表示沿用 harness 默认）
        Integer compactionTriggerTokens,
        String fallbackModelId,
        Integer maxRetries,
        Integer maxContextTokens,
        // AgentScope 2.0 能力开关（默认关闭，保持既有行为）
        boolean enablePlanMode,
        boolean enableTaskList,
        boolean enableMetaTool,
        // 异步工具卸载阈值（毫秒）：>0 时超时工具转后台，完成经唤醒恢复；0=关闭
        Integer asyncToolTimeoutMs,
        // 对话压缩（Java 官方推荐显式配置；2.0.3 字节码默认开，显式化防版本漂移）
        Integer compactionTriggerMessages,
        Integer compactionKeepMessages,
        // 大工具结果卸载（ToolResultEvictionConfig.defaults()，80K 字符阈值）
        boolean toolResultEviction
) {
}
