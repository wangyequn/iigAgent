package com.sinosoft.amp.openapi.ops;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * S7 指标：QPS / 延迟 / Token 用量 / 工具与 HITL。
 */
@Component
public class PlatformMetrics {

    private final MeterRegistry registry;
    private final RuntimeConfigService config;
    private final Timer chatLatency;
    private final Counter chatRequests;
    private final Counter chatErrors;
    private final Counter toolCalls;
    private final Counter hitlConfirms;
    private final Counter tokensPrompt;
    private final Counter tokensCompletion;

    public PlatformMetrics(MeterRegistry registry, RuntimeConfigService config) {
        this.registry = registry;
        this.config = config;
        this.chatLatency = Timer.builder("amp.chat.latency")
                .description("Chat call duration")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry);
        this.chatRequests = Counter.builder("amp.chat.requests").register(registry);
        this.chatErrors = Counter.builder("amp.chat.errors").register(registry);
        this.toolCalls = Counter.builder("amp.tool.calls").register(registry);
        this.hitlConfirms = Counter.builder("amp.hitl.confirms").register(registry);
        this.tokensPrompt = Counter.builder("amp.tokens.prompt").register(registry);
        this.tokensCompletion = Counter.builder("amp.tokens.completion").register(registry);
    }

    public void recordChat(String agentId, long durationMs, boolean error) {
        chatRequests.increment();
        if (error) {
            chatErrors.increment();
        }
        chatLatency.record(durationMs, TimeUnit.MILLISECONDS);
        registry.counter("amp.chat.requests.by_agent", "agentId", String.valueOf(agentId)).increment();
    }

    public void recordTokens(String agentId, int prompt, int completion) {
        if (prompt > 0) {
            tokensPrompt.increment(prompt);
            registry.counter("amp.tokens.prompt.by_agent", "agentId", String.valueOf(agentId)).increment(prompt);
        }
        if (completion > 0) {
            tokensCompletion.increment(completion);
            registry.counter("amp.tokens.completion.by_agent", "agentId", String.valueOf(agentId)).increment(completion);
        }
    }

    public void recordTool(String toolName) {
        toolCalls.increment();
        registry.counter("amp.tool.calls.by_name", "toolName", String.valueOf(toolName)).increment();
    }

    public void recordHitl(boolean approved) {
        hitlConfirms.increment();
        registry.counter("amp.hitl.by_result", "result", approved ? "approved" : "rejected").increment();
    }

    /** T7 沙箱执行耗时与失败率。 */
    public void recordSandbox(long durationMs, boolean error) {
        Timer.builder("amp.sandbox.duration")
                .description("Docker sandbox exec duration")
                .publishPercentiles(0.5, 0.95)
                .register(registry)
                .record(durationMs, TimeUnit.MILLISECONDS);
        registry.counter("amp.sandbox.execs", "result", error ? "error" : "ok").increment();
    }

    /** 粗估 token：字符数 / charsPerToken。 */
    public int estimateTokens(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        int charsPer = Math.max(1, config.tokenCharsPerToken());
        return (int) Math.ceil(text.length() / (double) charsPer);
    }

    public long costMicros(int totalTokens) {
        // 计费雏形：按 token 估成本（千分之一分单位）
        return (long) totalTokens * config.tokenCostPer1k() / 1000L;
    }
}
