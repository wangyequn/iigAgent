package com.sinosoft.amp.openapi.web;

import com.sinosoft.amp.openapi.agent.AgentInstance;
import com.sinosoft.amp.openapi.agent.AgentInstanceCache;
import com.sinosoft.amp.openapi.agent.CatalogRuntimeService;
import com.sinosoft.amp.openapi.catalog.CatalogException;
import com.sinosoft.amp.openapi.governance.ApprovalItem;
import com.sinosoft.amp.openapi.governance.GovernanceService;
import com.sinosoft.amp.openapi.security.AuthContext;
import com.sinosoft.amp.openapi.session.SessionContext;
import com.sinosoft.amp.openapi.session.SessionContextException;
import com.sinosoft.amp.openapi.session.SseReplayBuffer;
import com.sinosoft.amp.common.api.ApiError;
import com.sinosoft.amp.openapi.security.UnauthorizedException;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentEndEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.AgentStartEvent;
import io.agentscope.core.event.AllToolsDeniedEvent;
import io.agentscope.core.event.ConfirmResult;
import io.agentscope.core.event.CustomEvent;
import io.agentscope.core.event.ExceedMaxItersEvent;
import io.agentscope.core.event.HintBlockEvent;
import io.agentscope.core.event.ModelCallEndEvent;
import io.agentscope.core.event.RequireExternalExecutionEvent;
import io.agentscope.core.event.RequireUserConfirmEvent;
import io.agentscope.core.event.RequestStopEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.TextBlockEndEvent;
import io.agentscope.core.event.TextBlockStartEvent;
import io.agentscope.core.event.ThinkingBlockDeltaEvent;
import io.agentscope.core.event.ThinkingBlockEndEvent;
import io.agentscope.core.event.ThinkingBlockStartEvent;
import io.agentscope.core.event.ToolCallDeltaEvent;
import io.agentscope.core.event.ToolCallEndEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.ToolResultStartEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.state.AgentState;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

@RestController
@RequestMapping("/api/v1")
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    private final CatalogRuntimeService catalogRuntime;
    private final AgentInstanceCache instanceCache;
    private final GovernanceService governance;
    private final com.sinosoft.amp.openapi.ops.PlatformMetrics metrics;
    private final com.sinosoft.amp.openapi.session.SseReplayBuffer replayBuffer;
    private final com.sinosoft.amp.openapi.external.ExternalChatClient externalChat;
    private final com.sinosoft.amp.openapi.billing.QuotaService quotas;
    private final com.sinosoft.amp.openapi.ops.OtelBridge otel;
    private final com.sinosoft.amp.openapi.ops.SessionLogService sessionLogs;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;
    private final com.sinosoft.amp.openapi.session.SessionEventHub sessionHub;
    private final com.sinosoft.amp.openapi.session.SessionRunTracker runTracker;
    private final com.sinosoft.amp.openapi.session.AgentEventJson agentEventJson;
    private final com.sinosoft.amp.openapi.governance.ApprovalInbox approvalInbox;
    private final com.sinosoft.amp.openapi.session.SessionStateRuleScrubber ruleScrubber;

    public ChatController(
            CatalogRuntimeService catalogRuntime,
            AgentInstanceCache instanceCache,
            GovernanceService governance,
            com.sinosoft.amp.openapi.ops.PlatformMetrics metrics,
            com.sinosoft.amp.openapi.session.SseReplayBuffer replayBuffer,
            com.sinosoft.amp.openapi.external.ExternalChatClient externalChat,
            com.sinosoft.amp.openapi.billing.QuotaService quotas,
            com.sinosoft.amp.openapi.ops.OtelBridge otel,
            com.sinosoft.amp.openapi.ops.SessionLogService sessionLogs,
            com.fasterxml.jackson.databind.ObjectMapper objectMapper,
            com.sinosoft.amp.openapi.session.SessionEventHub sessionHub,
            com.sinosoft.amp.openapi.session.SessionRunTracker runTracker,
            com.sinosoft.amp.openapi.session.AgentEventJson agentEventJson,
            com.sinosoft.amp.openapi.governance.ApprovalInbox approvalInbox,
            com.sinosoft.amp.openapi.session.SessionStateRuleScrubber ruleScrubber) {
        this.catalogRuntime = catalogRuntime;
        this.instanceCache = instanceCache;
        this.governance = governance;
        this.metrics = metrics;
        this.replayBuffer = replayBuffer;
        this.externalChat = externalChat;
        this.quotas = quotas;
        this.otel = otel;
        this.sessionLogs = sessionLogs;
        this.objectMapper = objectMapper;
        this.sessionHub = sessionHub;
        this.runTracker = runTracker;
        this.agentEventJson = agentEventJson;
        this.approvalInbox = approvalInbox;
        this.ruleScrubber = ruleScrubber;
    }

    @GetMapping("/health/demo")
    public Map<String, Object> health() {
        // 保留兼容路径；HealthController 提供 /ready /replica
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("status", "UP");
        m.put("agent", "demo");
        m.put("replica", HealthController.replicaId());
        return m;
    }

    @PostMapping("/agents/{agentId}/messages")
    public Map<String, Object> message(
            @PathVariable String agentId,
            @Valid @RequestBody ChatRequest request) {
        return otel.inSpan("chat.messages",
                java.util.Map.of(
                        "amp.agent_id", agentId,
                        "amp.session_id", String.valueOf(request.sessionId()),
                        "amp.user_id", String.valueOf(request.userId())),
                () -> doMessage(agentId, request));
    }

    private Map<String, Object> doMessage(String agentId, ChatRequest request) {
        long start = System.currentTimeMillis();
        RuntimeContext ctx = context(agentId, request);
        String tenant = AuthContext.requireTenantId();
        if (quotas != null) {
            quotas.check(tenant);
        }

        // External 最小集：托管目录无此 Agent 时，按注册表转发 Chat
        boolean managed;
        try {
            catalogRuntime.resolve(tenant, agentId);
            managed = true;
        } catch (CatalogException e) {
            managed = false;
        }
        if (!managed && externalChat != null && externalChat.hasExternal(tenant, agentId)) {
            Map<String, Object> forwarded = externalChat.forwardMessage(
                    tenant, agentId, request.userId(), request.sessionId(), request.message());
            metrics.recordChat(agentId, System.currentTimeMillis() - start, false);
            log.info("chat.external agentId={} sessionId={} durationMs={}",
                    agentId, request.sessionId(), System.currentTimeMillis() - start);
            return forwarded;
        }

        AgentInstance instance = catalogRuntime.resolve(tenant, agentId);
        runTracker.register(request.sessionId(),
                new com.sinosoft.amp.openapi.session.SessionRunTracker.SessionMeta(tenant, agentId, request.userId()));
        runTracker.begin(request.sessionId());
        RequireUserConfirmEvent pause;
        String generateReason;
        Map<String, Object> out;
        try (AgentInstanceCache.Lease lease = instanceCache.lease(instance)) {
            List<AgentEvent> events = lease.agent()
                    .streamEvents(List.of(new UserMessage(request.message())), ctx)
                    .collectList()
                    .block();
            for (AgentEvent event : events) {
                maybePersistApproval(tenant, agentId, request.userId(), request.sessionId(), event);
                sessionHub.publish(request.sessionId(), event.getType().name(), agentEventJson.of(event));
            }
            sessionHub.complete(request.sessionId());
            pause = lastPause(events);
            Msg result = lastResultMsg(events);
            generateReason = result != null && result.getGenerateReason() != null
                    ? result.getGenerateReason().name() : null;
            String text = result == null ? "" : result.getTextContent();
            long ms = System.currentTimeMillis() - start;
            metrics.recordChat(agentId, ms, false);
            metrics.recordTokens(agentId,
                    metrics.estimateTokens(request.message()),
                    metrics.estimateTokens(text));
            if (quotas != null) {
                int p = metrics.estimateTokens(request.message());
                int c = metrics.estimateTokens(text);
                quotas.record(tenant, agentId, request.sessionId(), request.userId(),
                        p, c, metrics.costMicros(p + c), "managed");
            }
            log.info("chat.done agentId={} sessionId={} userId={} durationMs={} promptChars={} replyChars={} awaitConfirm={}",
                    agentId, request.sessionId(), request.userId(), ms,
                    request.message() == null ? 0 : request.message().length(),
                    text == null ? 0 : text.length(), pause != null);
            sessionLogs.sessionEvent("INFO", request.sessionId(), agentId, request.userId(),
                    "chat.done durationMs=" + ms + " replyChars=" + (text == null ? 0 : text.length())
                            + " awaitConfirm=" + (pause != null));
            out = new LinkedHashMap<>();
            out.put("agentId", agentId);
            out.put("sessionId", request.sessionId());
            out.put("reply", text == null ? "" : text);
            out.put("source", "managed");
            out.put("generateReason", generateReason);
        } catch (RuntimeException e) {
            metrics.recordChat(agentId, System.currentTimeMillis() - start, true);
            throw e;
        } finally {
            runTracker.end(request.sessionId());
        }
        if (pause != null) {
            metrics.recordHitl(false);
            throw new CatalogException("HITL_CONFIRM_REQUIRED",
                    "tool call(s) await confirmation: " + toolNames(pause)
                            + " (generateReason=" + generateReason + ")"
                            + "; list pending via GET .../approvals then POST .../confirm");
        }
        return out;
    }

    @PostMapping(value = "/agents/{agentId}/messages:stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(
            @PathVariable String agentId,
            @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId,
            @Valid @RequestBody ChatRequest request) {
        long start = System.currentTimeMillis();
        // 校验会话上下文（租户/agent）
        context(agentId, request);
        final String tenantForStream = AuthContext.requireTenantId();
        if (quotas != null) {
            quotas.check(tenantForStream);
        }
        final String userForStream = request.userId();
        final String sessionForStream = request.sessionId();
        SseEmitter emitter = new SseEmitter(0L);

        // --- 断线续传（§6.1）---
        long lastSeq = SseReplayBuffer.parseSeq(lastEventId, sessionForStream);
        if (lastEventId != null && !lastEventId.isBlank()) {
            if (lastSeq < 0) {
                // id 与 session 不匹配或格式非法
                sendResumeMiss(emitter);
                emitter.complete();
                return emitter;
            }
            SseReplayBuffer.SessionBuffer buf = replayBuffer.find(sessionForStream);
            if (buf == null) {
                sendResumeMiss(emitter);
                emitter.complete();
                return emitter;
            }
            List<SseReplayBuffer.SseFrame> pending = buf.after(lastSeq);
            if (pending.isEmpty() && !buf.isComplete()) {
                // 无增量且仍在跑：短窗内可再连；此处给 MISS 提示 history
                sendResumeMiss(emitter);
                emitter.complete();
                return emitter;
            }
            for (SseReplayBuffer.SseFrame f : pending) {
                sendFrame(emitter, f);
            }
            log.info("sse.resume sessionId={} lastSeq={} replayed={} complete={}",
                    sessionForStream, lastSeq, pending.size(), buf.isComplete());
            emitter.complete();
            return emitter;
        }

        // S9 External 流式：托管目录无此 Agent 时透传外部 SSE
        boolean managedForStream;
        try {
            catalogRuntime.resolve(tenantForStream, agentId);
            managedForStream = true;
        } catch (CatalogException e) {
            managedForStream = false;
        }
        if (!managedForStream && externalChat != null && externalChat.hasExternal(tenantForStream, agentId)) {
            SseEmitter extEmitter = new SseEmitter(0L);
            try {
                externalChat.forwardStream(tenantForStream, agentId, userForStream, sessionForStream,
                        request.message(), line -> {
                            try {
                                if (line != null && !line.isBlank()) {
                                    extEmitter.send(SseEmitter.event().data(line));
                                }
                            } catch (Exception io) {
                                log.debug("external sse send skip: {}", io.toString());
                            }
                        });
            } catch (RuntimeException e) {
                try {
                    extEmitter.send(SseEmitter.event().name("ERROR").data(buildErrorJson(e)));
                } catch (Exception ignored) {
                    // ignore
                }
                extEmitter.completeWithError(e);
                return extEmitter;
            }
            metrics.recordChat(agentId, System.currentTimeMillis() - start, false);
            extEmitter.complete();
            return extEmitter;
        }

        AgentInstance instance = catalogRuntime.resolve(tenantForStream, agentId);
        AgentInstanceCache.Lease lease = instanceCache.lease(instance);
        AtomicBoolean released = new AtomicBoolean(false);
        AtomicBoolean sawConfirm = new AtomicBoolean(false);
        SseReplayBuffer.SessionBuffer buffer = replayBuffer.open(sessionForStream);
        runTracker.register(sessionForStream,
                new com.sinosoft.amp.openapi.session.SessionRunTracker.SessionMeta(
                        tenantForStream, agentId, userForStream));
        runTracker.begin(sessionForStream);

        Runnable releaseOnce = () -> {
            if (released.compareAndSet(false, true)) {
                runTracker.end(sessionForStream);
                lease.close();
                buffer.markComplete();
                long ms = System.currentTimeMillis() - start;
                metrics.recordChat(agentId, ms, false);
                metrics.recordTokens(agentId,
                        metrics.estimateTokens(request.message()), 0);
                if (quotas != null) {
                    int p = metrics.estimateTokens(request.message());
                    quotas.record(tenantForStream, agentId, sessionForStream, userForStream,
                            p, 0, metrics.costMicros(p), "managed-stream");
                }
                log.info("stream.done agentId={} sessionId={} userId={} durationMs={} confirm={}",
                        agentId, sessionForStream, userForStream, ms, sawConfirm.get());
                sessionLogs.sessionEvent("INFO", sessionForStream, agentId, userForStream,
                        "stream.done durationMs=" + ms + " confirm=" + sawConfirm.get());
            }
        };

        try {
            RuntimeContext ctx = context(agentId, request);
            lease.agent().streamEvents(List.of(new UserMessage(request.message())), ctx)
                    .subscribe(
                            event -> {
                                if (event instanceof RequireUserConfirmEvent) {
                                    sawConfirm.set(true);
                                    metrics.recordHitl(false);
                                }
                                if (event instanceof ToolCallStartEvent toolStart) {
                                    String name = toolStart.getToolCallName() == null
                                            ? "unknown" : toolStart.getToolCallName();
                                    metrics.recordTool(name);
                                }
                                maybePersistApproval(tenantForStream, agentId, userForStream, sessionForStream, event);
                                String data = agentEventJson.of(event);
                                SseReplayBuffer.SseFrame frame =
                                        buffer.append(event.getType().name(), data);
                                sessionHub.fanout(sessionForStream, frame);
                                sendFrame(emitter, frame);
                            },
                            error -> {
                                log.error("stream failed, agentId={}", agentId, error);
                                String err = buildErrorJson(error);
                                SseReplayBuffer.SseFrame frame = buffer.append("ERROR", err);
                                sessionHub.fanout(sessionForStream, frame);
                                sendFrame(emitter, frame);
                                emitter.completeWithError(error);
                                metrics.recordChat(agentId, System.currentTimeMillis() - start, true);
                                releaseOnce.run();
                            },
                            () -> {
                                emitter.complete();
                                releaseOnce.run();
                            });
        } catch (RuntimeException e) {
            releaseOnce.run();
            throw e;
        }
        emitter.onCompletion(releaseOnce);
        emitter.onError(e -> releaseOnce.run());
        emitter.onTimeout(releaseOnce);
        return emitter;
    }

    /**
     * 只读订阅会话事件流（AgentScope 2.0 会话流）：不触发运行，
     * 先回放短窗缓冲再实时接收——供多 tab 同步、异步工具唤醒结果的观察端。
     * 触发运行仍走 POST .../messages:stream；两端共享同一帧编号，Last-Event-ID 通用。
     */
    @GetMapping(value = "/agents/{agentId}/sessions/{sessionId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter sessionEventStream(
            @PathVariable String agentId,
            @PathVariable String sessionId,
            @RequestParam String userId,
            @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId) {
        String tenant = AuthContext.requireTenantId();
        AuthContext.requireAgent(agentId);
        if (userId == null || userId.isBlank()) {
            throw new SessionContextException("userId is required");
        }
        SessionContext.require(tenant, userId, sessionId);
        SseEmitter emitter = new SseEmitter(0L);
        sessionHub.subscribe(sessionId, emitter, lastEventId);
        return emitter;
    }

    @GetMapping("/agents/{agentId}/sessions/{sessionId}/history")
    public HistoryReply history(
            @PathVariable String agentId,
            @PathVariable String sessionId,
            HistoryQuery query) {
        RuntimeContext ctx = context(agentId, query.tenantId(), query.userId(), sessionId);
        AgentInstance instance = catalogRuntime.resolve(AuthContext.requireTenantId(), agentId);
        AgentState state = instance.agent().getDelegate().getAgentState(ctx.getUserId(), ctx.getSessionId());
        List<HistoryItem> items = new ArrayList<>();
        String summary = null;
        if (state != null) {
            summary = state.getSummary();
            if (state.getContext() != null) {
                for (Msg msg : state.getContext()) {
                    items.add(new HistoryItem(
                            msg.getRole() == null ? null : msg.getRole().name(),
                            msg.getTextContent(),
                            msg.getTimestamp()));
                }
            }
        }
        return new HistoryReply(agentId, sessionId, summary, items);
    }

    @PostMapping("/agents/{agentId}/sessions/{sessionId}/interrupt")
    public Map<String, Object> interrupt(
            @PathVariable String agentId,
            @PathVariable String sessionId,
            @Valid @RequestBody SessionUserRequest request) {
        RuntimeContext ctx = context(agentId, request.tenantId(), request.userId(), sessionId);
        AgentInstance instance = catalogRuntime.resolve(AuthContext.requireTenantId(), agentId);
        instance.agent().interrupt(ctx.getUserId(), ctx.getSessionId());
        return Map.of(
                "agentId", agentId,
                "sessionId", sessionId,
                "status", "INTERRUPT_REQUESTED");
    }

    /**
     * 清空指定会话的对话上下文（AgentScope 官方 clearContext）：
     * 该会话状态重置为初始槽位（对话历史清空、权限回到 build 注入的初始规则），
     * 经 stateStore 持久化——下一次调用即"失忆"重新开始，适合误会话重置与测试。
     */
    @PostMapping("/agents/{agentId}/sessions/{sessionId}/clear-context")
    public Map<String, Object> clearContext(
            @PathVariable String agentId,
            @PathVariable String sessionId,
            @Valid @RequestBody SessionUserRequest request) {
        RuntimeContext ctx = context(agentId, request.tenantId(), request.userId(), sessionId);
        AgentInstance instance = catalogRuntime.resolve(AuthContext.requireTenantId(), agentId);
        try (AgentInstanceCache.Lease lease = instanceCache.lease(instance)) {
            lease.agent().clearContext(ctx);
        }
        log.info("context cleared agentId={} sessionId={} userId={}",
                agentId, sessionId, request.userId());
        return Map.of(
                "agentId", agentId,
                "sessionId", sessionId,
                "status", "CONTEXT_CLEARED");
    }

    @PostMapping("/agents/{agentId}/sessions/{sessionId}/confirm")
    public ChatReply confirm(
            @PathVariable String agentId,
            @PathVariable String sessionId,
            @RequestParam(required = false) String approvalId,
            @RequestParam(required = false) String idempotencyKey,
            @Valid @RequestBody ConfirmRequest request) {
        RuntimeContext ctx = context(agentId, request.tenantId(), request.userId(), sessionId);
        String key = idempotencyKey != null && !idempotencyKey.isBlank()
                ? idempotencyKey
                : request.idempotencyKey();
        if (key != null && !key.isBlank()) {
            governance.findApprovalByIdempotency(key).ifPresent(prev -> {
                if (!ApprovalItem.PENDING.equals(prev.status())) {
                    throw new CatalogException("APPROVAL_ALREADY_RESOLVED",
                            "approval already resolved: " + prev.status());
                }
            });
        }
        AgentInstance instance = catalogRuntime.resolve(AuthContext.requireTenantId(), agentId);

        List<ConfirmResult> results = new ArrayList<>();
        // acceptRule 采纳的建议规则：恢复成功后写 permission_rule（替换同工具 ASK）并驱逐实例即时生效
        List<io.agentscope.core.permission.PermissionRule> acceptedRules = new ArrayList<>();
        boolean rulePersisted = false;
        boolean hasToolCalls = request.toolCalls() != null && !request.toolCalls().isEmpty();
        if (hasToolCalls) {
            for (ToolCallDecision decision : request.toolCalls()) {
                // content 是 ToolExecutor 做 schema 校验的入参 JSON 串，缺失会报 "argument content is null"
                ToolUseBlock block = ToolUseBlock.builder()
                        .id(decision.toolCallId())
                        .name(decision.toolName())
                        .input(decision.input() == null ? Map.of() : decision.input())
                        .content(toJson(decision.input()))
                        .build();
                if (acceptRule(decision, request)) {
                    // 官方建议规则语义：随确认回传引擎；同时平台落库（见下），保证跨调用生效
                    List<io.agentscope.core.permission.PermissionRule> rules =
                            suggestedRules(instance, decision.toolName(), decision.input());
                    acceptedRules.addAll(rules);
                    results.add(new ConfirmResult(decision.confirmed(), block, rules));
                } else {
                    results.add(new ConfirmResult(decision.confirmed(), block));
                }
            }
            metrics.recordHitl(request.toolCalls().stream().anyMatch(ToolCallDecision::confirmed));
        } else if (approvalId != null && !approvalId.isBlank()) {
            ApprovalItem item = governance.findApproval(approvalId).orElse(null);
            if (item == null) {
                throw new CatalogException("APPROVAL_NOT_FOUND", "approval not found: " + approvalId);
            }
            ToolUseBlock block = ToolUseBlock.builder()
                    .id(item.toolCallId())
                    .name(item.toolName())
                    .input(parseInput(item.toolInput()))
                    .content(blankToEmptyJson(item.toolInput()))
                    .build();
            boolean confirmed = request.confirmed() == null || Boolean.TRUE.equals(request.confirmed());
            List<io.agentscope.core.permission.PermissionRule> itemRules =
                    Boolean.TRUE.equals(request.acceptRule())
                            ? suggestedRules(instance, item.toolName(), parseInput(item.toolInput()))
                            : List.of();
            acceptedRules.addAll(itemRules);
            results.add(buildConfirmResult(confirmed, block,
                    itemRules.isEmpty() ? null : itemRules));
        } else if (request.confirmed() != null) {
            String tenant = AuthContext.requireTenantId();
            List<ApprovalItem> pendings = governance.pending(tenant, agentId, sessionId);
            if (pendings.isEmpty()) {
                throw new CatalogException("APPROVAL_NOT_FOUND", "no pending approval for session");
            }
            for (ApprovalItem item : pendings) {
                ToolUseBlock block = ToolUseBlock.builder()
                        .id(item.toolCallId())
                        .name(item.toolName())
                        .input(parseInput(item.toolInput()))
                        .content(blankToEmptyJson(item.toolInput()))
                        .build();
                List<io.agentscope.core.permission.PermissionRule> itemRules =
                        Boolean.TRUE.equals(request.acceptRule())
                                ? suggestedRules(instance, item.toolName(), parseInput(item.toolInput()))
                                : List.of();
                acceptedRules.addAll(itemRules);
                results.add(buildConfirmResult(Boolean.TRUE.equals(request.confirmed()), block,
                        itemRules.isEmpty() ? null : itemRules));
            }
        }

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put(Msg.METADATA_CONFIRM_RESULTS, results);
        if (key != null && !key.isBlank()) {
            metadata.put(Msg.METADATA_CONFIRM_REQUEST_REPLY_ID, key);
        }
        UserMessage resume = UserMessage.builder()
                .name("user")
                .textContent(request.note() == null || request.note().isBlank()
                        ? (results.stream().anyMatch(ConfirmResult::isConfirmed) ? "用户已批准该工具调用" : "用户拒绝了该工具调用")
                        : request.note())
                .metadata(metadata)
                .build();
        RequireUserConfirmEvent rePause;
        String confirmTenant = AuthContext.requireTenantId();
        runTracker.register(sessionId,
                new com.sinosoft.amp.openapi.session.SessionRunTracker.SessionMeta(
                        confirmTenant, agentId, request.userId()));
        runTracker.begin(sessionId);
        try (AgentInstanceCache.Lease lease = instanceCache.lease(instance)) {
            List<AgentEvent> events = lease.agent()
                    .streamEvents(List.of(resume), ctx)
                    .collectList()
                    .block();
            String tenant = confirmTenant;
            for (AgentEvent event : events) {
                maybePersistApproval(tenant, agentId, request.userId(), sessionId, event);
                sessionHub.publish(sessionId, event.getType().name(), agentEventJson.of(event));
            }
            sessionHub.complete(sessionId);
            rePause = lastPause(events);
            Msg result = lastResultMsg(events);
            String text = result == null ? "" : result.getTextContent();
            // 恢复执行过了（无论工具成败），落审批状态
            if (hasToolCalls) {
                for (ToolCallDecision decision : request.toolCalls()) {
                    resolveApproval(decision, request, key, agentId, sessionId);
                }
            } else if (approvalId != null && !approvalId.isBlank()) {
                boolean confirmed = request.confirmed() == null || Boolean.TRUE.equals(request.confirmed());
                governance.decide(approvalId, confirmed, request.note(), key);
            } else if (request.confirmed() != null) {
                for (ApprovalItem item : governance.pending(tenant, agentId, sessionId)) {
                    governance.decide(item.id(), Boolean.TRUE.equals(request.confirmed()), request.note(), key);
                }
            }
            // acceptRule：建议规则落 permission_rule（upsertRule 同工具同内容行为唯一 → ASK 被 ALLOW 替换）
            if (!acceptedRules.isEmpty()) {
                for (io.agentscope.core.permission.PermissionRule r : acceptedRules) {
                    governance.upsertRule(confirmTenant, agentId, r.toolName(),
                            r.behavior() == null ? "ALLOW" : r.behavior().name(),
                            r.ruleContent() == null || r.ruleContent().isBlank() ? "*" : r.ruleContent(),
                            r.source() == null || r.source().isBlank() ? "session" : r.source());
                }
                rulePersisted = true;
                log.info("suggested rules persisted tenant={} agent={} tools={}",
                        confirmTenant, agentId,
                        acceptedRules.stream().map(io.agentscope.core.permission.PermissionRule::toolName)
                                .distinct().toList());
            }
            if (rePause != null) {
                // 恢复后再次进入确认暂停（如工具失败后模型重试）：新审批已由上面落库，明确返回 409
                metrics.recordHitl(false);
                throw new CatalogException("HITL_CONFIRM_REQUIRED",
                        "tool call(s) await confirmation after resume: " + toolNames(rePause));
            }
            return new ChatReply(agentId, text == null ? "" : text, sessionId,
                    result != null && result.getGenerateReason() != null
                            ? result.getGenerateReason().name() : null);
        } catch (IllegalStateException e) {
            throw new CatalogException("HITL_RESUME_INVALID", e.getMessage());
        } finally {
            runTracker.end(sessionId);
            if (rulePersisted) {
                // 规则在 build 时注入实例，驱逐后下一次请求重建才生效（lease 已关闭，可安全逐出）
                instanceCache.evictTenant(confirmTenant);
                log.info("instance evicted after suggested rules tenant={} agent={}", confirmTenant, agentId);
                // 陈旧会话状态里的 ask_rules 会压制新 ALLOW（引擎 ASK 优先），按新行为重同步状态
                acceptedRules.forEach(r -> ruleScrubber.resyncTool(
                        r.toolName(),
                        r.behavior() == null ? "ALLOW" : r.behavior().name()));
            }
        }
    }

    private static RequireUserConfirmEvent lastPause(List<AgentEvent> events) {
        if (events == null) {
            return null;
        }
        RequireUserConfirmEvent pause = null;
        for (AgentEvent event : events) {
            if (event instanceof RequireUserConfirmEvent confirm) {
                pause = confirm;
            }
        }
        return pause;
    }

    private static Msg lastResultMsg(List<AgentEvent> events) {
        if (events == null) {
            return null;
        }
        Msg result = null;
        for (AgentEvent event : events) {
            if (event instanceof AgentResultEvent agentResult && agentResult.getResult() != null) {
                result = agentResult.getResult();
            }
        }
        return result;
    }

    private static String toolNames(RequireUserConfirmEvent pause) {
        if (pause.getToolCalls() == null || pause.getToolCalls().isEmpty()) {
            return "unknown";
        }
        return pause.getToolCalls().stream()
                .map(ToolUseBlock::getName)
                .distinct()
                .collect(java.util.stream.Collectors.joining(", "));
    }

    private static String blankToEmptyJson(String json) {
        return json == null || json.isBlank() ? "{}" : json;
    }

    private static boolean acceptRule(ToolCallDecision decision, ConfirmRequest request) {
        if (decision != null && decision.acceptRule() != null) {
            return decision.acceptRule();
        }
        return Boolean.TRUE.equals(request.acceptRule());
    }

    private static ConfirmResult buildConfirmResult(boolean confirmed, ToolUseBlock block,
                                                    java.util.List<io.agentscope.core.permission.PermissionRule> rules) {
        return rules == null
                ? new ConfirmResult(confirmed, block)
                : new ConfirmResult(confirmed, block, rules);
    }

    /**
     * 建议规则（AgentScope 官方 suggested_rules 语义）：优先用工具自身的 generateSuggestions
     * （文件类工具产出路径级规则），否则回退为「该工具整体 ALLOW（ruleContent=null 即任意入参）」，
     * source=session 标识由本次会话确认产生。
     */
    private java.util.List<io.agentscope.core.permission.PermissionRule> suggestedRules(
            AgentInstance instance, String toolName, Map<String, Object> input) {
        java.util.List<io.agentscope.core.permission.PermissionRule> out = new ArrayList<>();
        try {
            io.agentscope.core.tool.AgentTool tool = instance.agent().getToolkit().getTool(toolName);
            if (tool instanceof io.agentscope.core.tool.ToolBase base) {
                java.util.List<io.agentscope.core.permission.PermissionRule> generated =
                        base.generateSuggestions(input == null ? Map.of() : input);
                if (generated != null) {
                    out.addAll(generated);
                }
            }
        } catch (RuntimeException e) {
            log.debug("generateSuggestions failed tool={}: {}", toolName, e.toString());
        }
        if (out.isEmpty()) {
            out.add(new io.agentscope.core.permission.PermissionRule(
                    toolName, null, io.agentscope.core.permission.PermissionBehavior.ALLOW, "session"));
        }
        return out;
    }

    @GetMapping("/agents/{agentId}/sessions/{sessionId}/approvals")
    public Map<String, Object> listSessionApprovals(
            @PathVariable String agentId,
            @PathVariable String sessionId,
            @RequestParam String userId) {
        String tenant = AuthContext.requireTenantId();
        AuthContext.requireAgent(agentId);
        if (userId == null || userId.isBlank()) {
            throw new SessionContextException("userId is required");
        }
        List<Map<String, Object>> items = new ArrayList<>();
        AgentInstance instance = null;
        try {
            instance = catalogRuntime.resolve(tenant, agentId);
        } catch (CatalogException e) {
            // 实例不可用时省略 suggestedRules，不影响审批列表本身
        }
        for (ApprovalItem a : governance.pending(tenant, agentId, sessionId)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", a.id());
            row.put("toolCallId", a.toolCallId());
            row.put("toolName", a.toolName());
            row.put("toolInput", a.toolInput());
            row.put("status", a.status());
            row.put("replyId", a.replyId());
            if (instance != null) {
                List<Map<String, Object>> rules = new ArrayList<>();
                for (io.agentscope.core.permission.PermissionRule r
                        : suggestedRules(instance, a.toolName(), parseInput(a.toolInput()))) {
                    Map<String, Object> rule = new LinkedHashMap<>();
                    rule.put("toolName", r.toolName());
                    rule.put("ruleContent", r.ruleContent());
                    rule.put("behavior", r.behavior() == null ? null : r.behavior().name());
                    rule.put("source", r.source());
                    rules.add(rule);
                }
                row.put("suggestedRules", rules);
            }
            items.add(row);
        }
        return Map.of("agentId", agentId, "sessionId", sessionId, "pending", items);
    }

    private void resolveApproval(ToolCallDecision decision, ConfirmRequest request, String key,
                                 String agentId, String sessionId) {
        if (decision == null || decision.toolCallId() == null) {
            return;
        }
        try {
            String tenant = AuthContext.requireTenantId();
            ApprovalItem byKey = key == null ? null : governance.findApprovalByIdempotency(key).orElse(null);
            List<ApprovalItem> pendings = governance.pending(tenant, agentId, sessionId);
            ApprovalItem target = pendings.stream()
                    .filter(p -> decision.toolCallId().equals(p.toolCallId()))
                    .findFirst()
                    .orElse(byKey);
            if (target == null) {
                return;
            }
            governance.decide(target.id(), decision.confirmed(), request.note(), key);
        } catch (CatalogException e) {
            if (!"APPROVAL_ALREADY_RESOLVED".equals(e.code())) {
                throw e;
            }
        }
    }

    private void maybePersistApproval(String tenant, String agentId, String userId, String sessionId, AgentEvent event) {
        approvalInbox.capture(tenant, agentId, userId, sessionId, event);
    }

    private String toJson(Object input) {
        try {
            return objectMapper.writeValueAsString(input == null ? Map.of() : input);
        } catch (Exception e) {
            return "{}";
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseInput(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (Exception e) {
            return Map.of();
        }
    }

    private RuntimeContext context(String agentId, ChatRequest request) {
        return context(agentId, request.tenantId(), request.userId(), request.sessionId());
    }

    private RuntimeContext context(String agentId, String tenantId, String userId, String sessionId) {
        String boundTenant = AuthContext.requireTenantId();
        try {
            AuthContext.requireAgent(agentId);
        } catch (UnauthorizedException e) {
            // External 最小集：已注册且健康的 External 名称视作可访问
            if (externalChat == null || !externalChat.hasExternal(boundTenant, agentId)) {
                throw e;
            }
        }
        if (tenantId != null && !tenantId.isBlank() && !tenantId.equals(boundTenant)) {
            throw new SessionContextException("tenantId does not match api key tenant");
        }
        return SessionContext.require(boundTenant, userId, sessionId);
    }


    private String buildErrorJson(Throwable error) {
        try {
            return objectMapper.writeValueAsString(
                    ApiError.of("STREAM_ERROR", error.getMessage(), requestId()));
        } catch (Exception e) {
            return "{\"code\":\"STREAM_ERROR\"}";
        }
    }

    private void sendFrame(SseEmitter emitter, SseReplayBuffer.SseFrame frame) {
        try {
            emitter.send(SseEmitter.event()
                    .id(frame.id)
                    .name(frame.event)
                    .data(frame.data));
        } catch (IOException e) {
            // client gone；缓冲仍可供续传
        }
    }

    private void sendResumeMiss(SseEmitter emitter) {
        try {
            emitter.send(SseEmitter.event()
                    .name("ERROR")
                    .data("{\"code\":\"SSE_RESUME_MISS\",\"message\":\"replay buffer expired or unknown; call history\"}"));
        } catch (IOException ignored) {
        }
    }

    private void sendError(SseEmitter emitter, Throwable error) {
        try {
            emitter.send(
                    SseEmitter.event()
                            .name("ERROR")
                            .data(buildErrorJson(error)));
        } catch (IOException ignored) {
        }
    }

    @ExceptionHandler(SessionContextException.class)
    public ResponseEntity<ApiError> handleSession(SessionContextException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiError.of("SESSION_CONTEXT_INVALID", e.getMessage(), requestId()));
    }

    @ExceptionHandler(org.springframework.web.bind.MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(
            org.springframework.web.bind.MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(f -> f.getField() + " " + f.getDefaultMessage())
                .orElse("validation failed");
        String code = msg.contains("sessionId") || msg.contains("userId")
                ? "SESSION_CONTEXT_INVALID"
                : "VALIDATION_FAILED";
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiError.of(code, msg, requestId()));
    }

    @ExceptionHandler(org.springframework.web.bind.MissingServletRequestParameterException.class)
    public ResponseEntity<ApiError> handleMissingParam(
            org.springframework.web.bind.MissingServletRequestParameterException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiError.of("SESSION_CONTEXT_INVALID", e.getMessage(), requestId()));
    }

    @ExceptionHandler(UnauthorizedException.class)
    public ResponseEntity<ApiError> handleUnauthorized(UnauthorizedException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ApiError.of("FORBIDDEN", e.getMessage(), requestId()));
    }

    @ExceptionHandler(CatalogException.class)
    public ResponseEntity<ApiError> handleCatalog(CatalogException e) {
        HttpStatus status = switch (e.code()) {
            case "AGENT_NOT_FOUND", "VERSION_NOT_FOUND", "NO_RELEASE", "APPROVAL_NOT_FOUND" -> HttpStatus.NOT_FOUND;
            case "EXTERNAL_CHAT_FAILED", "EXTERNAL_UNHEALTHY" -> HttpStatus.BAD_GATEWAY;
            case "APPROVAL_ALREADY_RESOLVED" -> HttpStatus.CONFLICT;
            case "HITL_RESUME_INVALID" -> HttpStatus.CONFLICT;
            case "HITL_CONFIRM_REQUIRED" -> HttpStatus.CONFLICT;
            case "AGENT_DISABLED" -> HttpStatus.CONFLICT;
            case "VERSION_DRAINING", "VERSION_NOT_READY", "AGENT_EXISTS", "VERSION_EXISTS" -> HttpStatus.CONFLICT;
            default -> HttpStatus.BAD_REQUEST;
        };
        return ResponseEntity.status(status).body(ApiError.of(e.code(), e.getMessage(), requestId()));
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<ApiError> handleRuntime(RuntimeException e) {
        log.error("request failed", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiError.of("INTERNAL_ERROR", e.getMessage(), requestId()));
    }

    private static String requestId() {
        ServletRequestAttributes attrs =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attrs != null && attrs.getRequest().getHeader("X-Request-Id") != null) {
            return attrs.getRequest().getHeader("X-Request-Id");
        }
        return UUID.randomUUID().toString();
    }

    public record ChatRequest(
            String tenantId,
            @NotBlank String userId,
            @NotBlank String sessionId,
            @NotBlank String message
    ) {
    }

    public record SessionUserRequest(
            String tenantId,
            @NotBlank String userId
    ) {
    }

    public record HistoryQuery(
            String tenantId,
            @NotBlank String userId
    ) {
    }

    public record ConfirmRequest(
            String tenantId,
            @NotBlank String userId,
            List<ToolCallDecision> toolCalls,
            String note,
            Boolean confirmed,
            String idempotencyKey,
            // true=接受建议规则：该工具本会话后续调用自动放行（ASK 一键免问）
            Boolean acceptRule
    ) {
    }

    public record ToolCallDecision(
            @NotBlank String toolCallId,
            @NotBlank String toolName,
            boolean confirmed,
            Map<String, Object> input,
            Boolean acceptRule
    ) {
    }

    public record ChatReply(String agentId, String reply, String sessionId, String generateReason) {
    }

    public record HistoryReply(
            String agentId,
            String sessionId,
            String summary,
            List<HistoryItem> messages
    ) {
    }

    public record HistoryItem(String role, String text, String timestamp) {
    }
}
