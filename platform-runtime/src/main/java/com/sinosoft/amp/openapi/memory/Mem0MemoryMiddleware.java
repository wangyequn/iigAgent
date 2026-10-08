package com.sinosoft.amp.openapi.memory;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.memory.LongTermMemoryTools;
import io.agentscope.core.memory.mem0.Mem0LongTermMemory;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.MiddlewareBase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Mem0 长期记忆中间件（对齐官方 {@code StaticLongTermMemoryHook} 语义，改用 2.0 Middleware）。
 *
 * <ul>
 *   <li>对话前：语义召回并注入 {@code <long_term_memory>} 上下文</li>
 *   <li>对话后：record 抽取沉淀（infer）</li>
 * </ul>
 *
 * <p>身份取 {@link MemoryScope}（ChatController 绑定）或 {@link RuntimeContext}。
 * 失败只打日志，不阻断对话。
 */
public class Mem0MemoryMiddleware implements MiddlewareBase {

    private static final Logger log = LoggerFactory.getLogger(Mem0MemoryMiddleware.class);

    private final ScopedMem0LongTermMemory memory;
    private final Mem0Properties properties;
    private final String defaultTenantId;

    public Mem0MemoryMiddleware(ScopedMem0LongTermMemory memory, Mem0Properties properties, String defaultTenantId) {
        this.memory = memory;
        this.properties = properties;
        this.defaultTenantId = defaultTenantId == null ? "acme" : defaultTenantId;
    }

    @Override
    public int order() {
        // 较高 order 更靠外，先于业务中间件注入记忆
        return 50;
    }

    @Override
    public Flux<AgentEvent> onAgent(
            Agent agent,
            RuntimeContext ctx,
            AgentInput input,
            Function<AgentInput, Flux<AgentEvent>> next) {
        if (memory == null || !memory.properties().isConfigured()) {
            return next.apply(input);
        }
        MemoryScope.Scope scope = resolveScope(agent, ctx);
        List<Msg> original = input == null || input.msgs() == null ? List.of() : input.msgs();
        boolean recall = Boolean.TRUE.equals(properties.autoRecall());
        boolean capture = Boolean.TRUE.equals(properties.autoCapture());

        AgentInput effective = input;
        MemoryScope.bind(scope.tenantId(), scope.userId(), scope.agentId(), scope.sessionId());
        try {
            if (recall && !original.isEmpty()) {
                try {
                    Msg query = lastUser(original);
                    if (query != null) {
                        String retrieved = memory.retrieve(scope, query).block();
                        if (retrieved != null && !retrieved.isBlank()) {
                            List<Msg> enriched = new ArrayList<>(original.size() + 1);
                            enriched.add(memoryContextMsg(retrieved));
                            enriched.addAll(original);
                            effective = new AgentInput(enriched);
                        }
                    }
                } catch (RuntimeException e) {
                    log.warn("mem0 auto-recall failed scope={}: {}", scope, e.toString());
                    effective = input;
                }
            }

            MemoryScope.Scope captureScope = scope;
            List<Msg> captureMsgs = original;
            return next.apply(effective)
                    .doOnComplete(() -> {
                        if (!capture) {
                            return;
                        }
                        try {
                            memory.record(captureScope, captureMsgs).block();
                        } catch (RuntimeException e) {
                            log.warn("mem0 auto-capture failed scope={}: {}", captureScope, e.toString());
                        }
                    })
                    .doFinally(s -> MemoryScope.clear());
        } catch (RuntimeException e) {
            MemoryScope.clear();
            throw e;
        }
    }

    private MemoryScope.Scope resolveScope(Agent agent, RuntimeContext ctx) {
        MemoryScope.Scope bound = MemoryScope.get();
        String tenant = bound != null && bound.tenantId() != null ? bound.tenantId() : defaultTenantId;
        String userId = bound != null && bound.userId() != null
                ? bound.userId()
                : (ctx != null ? ctx.getUserId() : null);
        String sessionId = bound != null && bound.sessionId() != null
                ? bound.sessionId()
                : (ctx != null ? ctx.getSessionId() : null);
        String agentId = bound != null && bound.agentId() != null
                ? bound.agentId()
                : (agent != null ? agent.getName() : "agent");
        return new MemoryScope.Scope(tenant, userId, agentId, sessionId);
    }

    private static Msg lastUser(List<Msg> msgs) {
        for (int i = msgs.size() - 1; i >= 0; i--) {
            Msg m = msgs.get(i);
            if (m != null && m.getRole() == MsgRole.USER && m.getTextContent() != null
                    && !m.getTextContent().isBlank()) {
                return m;
            }
        }
        return null;
    }

    private static Msg memoryContextMsg(String memoryText) {
        String wrapped = LongTermMemoryTools.wrap(memoryText);
        return Msg.builder()
                .role(MsgRole.USER)
                .name("long_term_memory")
                .content(TextBlock.builder().text(wrapped).build())
                .build();
    }

    /** 官方工具适配器：recordToMemory / retrieveFromMemory。 */
    public static LongTermMemoryTools tools(ScopedMem0LongTermMemory memory) {
        return new LongTermMemoryTools(memory);
    }
}
