package com.sinosoft.amp.openapi.agent;

import com.sinosoft.amp.openapi.governance.ApprovalInbox;
import com.sinosoft.amp.openapi.ops.PlatformMetrics;
import com.sinosoft.amp.openapi.session.AgentEventJson;
import com.sinosoft.amp.openapi.session.SessionContext;
import com.sinosoft.amp.openapi.session.SessionEventHub;
import com.sinosoft.amp.openapi.session.SessionRunTracker;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.RequireUserConfirmEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.harness.agent.bus.MessageBus;
import io.agentscope.harness.agent.gateway.WakeupDispatcher;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Objects;

/**
 * 异步工具唤醒恢复（AgentScope 2.0 后台工具卸载的消费端）：
 * 订阅 MessageBus 的 wakeup 信号，按 sessionId 找回会话归属，以空消息列表
 * （{@code streamEvents(List.of(), ctx)}，与 HarnessGateway.runWakeup 同款）恢复运行，
 * 事件经 {@link SessionEventHub} 扇出给只读订阅者，从而在无人发新消息时续跑并可见。
 */
@Component
public class AgentWakeupService implements WakeupDispatcher.WakeupTarget, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(AgentWakeupService.class);

    private final MessageBus messageBus;
    private final CatalogRuntimeService catalogRuntime;
    private final AgentInstanceCache instanceCache;
    private final SessionRunTracker tracker;
    private final SessionEventHub hub;
    private final AgentEventJson eventJson;
    private final ApprovalInbox approvalInbox;
    private final PlatformMetrics metrics;

    private volatile WakeupDispatcher dispatcher;

    public AgentWakeupService(
            MessageBus messageBus,
            CatalogRuntimeService catalogRuntime,
            AgentInstanceCache instanceCache,
            SessionRunTracker tracker,
            SessionEventHub hub,
            AgentEventJson eventJson,
            ApprovalInbox approvalInbox,
            PlatformMetrics metrics) {
        this.messageBus = messageBus;
        this.catalogRuntime = catalogRuntime;
        this.instanceCache = instanceCache;
        this.tracker = tracker;
        this.hub = hub;
        this.eventJson = eventJson;
        this.approvalInbox = approvalInbox;
        this.metrics = metrics;
    }

    @PostConstruct
    void start() {
        try {
            dispatcher = new WakeupDispatcher(messageBus, this);
            dispatcher.start();
            log.info("wakeup dispatcher started");
        } catch (RuntimeException e) {
            dispatcher = null;
            log.warn("wakeup dispatcher start failed (async offload resume disabled): {}", e.toString());
        }
    }

    @PreDestroy
    @Override
    public void close() {
        WakeupDispatcher d = dispatcher;
        dispatcher = null;
        if (d != null) {
            try {
                d.close();
            } catch (RuntimeException e) {
                log.warn("wakeup dispatcher close failed: {}", e.toString());
            }
        }
    }

    @Override
    public boolean isSessionRunning(String sessionId) {
        return tracker.isRunning(sessionId);
    }

    @Override
    public Mono<Msg> runWakeup(String sessionId) {
        return runWakeup(sessionId, null);
    }

    @Override
    public Mono<Msg> runWakeup(String sessionId, String userId) {
        SessionRunTracker.SessionMeta meta = tracker.meta(sessionId);
        if (meta == null) {
            log.debug("wakeup skip: no session meta sessionId={}", sessionId);
            return Mono.empty();
        }
        final String uid = userId != null && !userId.isBlank() ? userId : meta.userId();
        final AgentInstance instance;
        final AgentInstanceCache.Lease lease;
        final RuntimeContext ctx;
        try {
            instance = catalogRuntime.resolve(meta.tenantId(), meta.agentId());
            lease = instanceCache.lease(instance);
            ctx = SessionContext.require(meta.tenantId(), uid, sessionId);
        } catch (RuntimeException e) {
            log.warn("wakeup resolve failed sessionId={}: {}", sessionId, e.toString());
            return Mono.empty();
        }
        tracker.begin(sessionId);
        long start = System.currentTimeMillis();
        final String tenant = meta.tenantId();
        final String agentId = meta.agentId();
        return lease.agent()
                .streamEvents(List.of(), ctx)
                .collectList()
                .doOnNext(events -> {
                    boolean sawConfirm = false;
                    for (AgentEvent event : events) {
                        if (event instanceof RequireUserConfirmEvent) {
                            sawConfirm = true;
                            metrics.recordHitl(false);
                        }
                        if (event instanceof ToolCallStartEvent toolStart) {
                            String name = toolStart.getToolCallName() == null
                                    ? "unknown" : toolStart.getToolCallName();
                            metrics.recordTool(name);
                        }
                        approvalInbox.capture(tenant, agentId, uid, sessionId, event);
                        hub.publish(sessionId, event.getType().name(), eventJson.of(event));
                    }
                    hub.complete(sessionId);
                    long ms = System.currentTimeMillis() - start;
                    metrics.recordChat(agentId, ms, false);
                    log.info("wakeup.done agentId={} sessionId={} durationMs={} events={} confirm={}",
                            agentId, sessionId, ms, events.size(), sawConfirm);
                })
                .map(events -> events.stream()
                        .filter(AgentResultEvent.class::isInstance)
                        .map(AgentResultEvent.class::cast)
                        .map(AgentResultEvent::getResult)
                        .filter(Objects::nonNull)
                        .reduce((a, b) -> b)
                        .orElse(null))
                .filter(Objects::nonNull)
                .doOnError(e -> log.warn("wakeup run failed agentId={} sessionId={}: {}",
                        agentId, sessionId, e.toString()))
                .doFinally(signal -> {
                    tracker.end(sessionId);
                    lease.close();
                });
    }
}
