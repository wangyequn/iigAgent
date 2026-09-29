package com.sinosoft.amp.openapi.web;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sinosoft.amp.openapi.agent.AgentInstance;
import com.sinosoft.amp.openapi.agent.AgentInstanceCache;
import com.sinosoft.amp.openapi.agent.CatalogRuntimeService;
import com.sinosoft.amp.openapi.catalog.CatalogException;
import com.sinosoft.amp.openapi.ops.PlatformMetrics;
import com.sinosoft.amp.openapi.security.AuthContext;
import com.sinosoft.amp.openapi.security.UnauthorizedException;
import com.sinosoft.amp.openapi.session.SessionContext;
import com.sinosoft.amp.openapi.session.SessionContextException;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.RequireUserConfirmEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * OpenAI 兼容入口：让业务/网关用 OpenAI Chat Completions 风格调用托管 Agent。
 * 不改动 ChatController；model → agentId；从最后一条 user message 取文本调用现有 call。
 */
@RestController
@RequestMapping({"/api/v1/chat", "/v1/chat"})
public class OpenAiCompatController {

    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatController.class);
    public static final String DONE_FRAME = "data: [DONE]\n\n";
    public static final String DEFAULT_USER = "openai-compat";

    private final CatalogRuntimeService catalogRuntime;
    private final AgentInstanceCache instanceCache;
    private final PlatformMetrics metrics;
    private final ObjectMapper objectMapper;
    private final com.sinosoft.amp.openapi.session.SessionEventHub sessionHub;
    private final com.sinosoft.amp.openapi.session.SessionRunTracker runTracker;
    private final com.sinosoft.amp.openapi.governance.ApprovalInbox approvalInbox;
    private final com.sinosoft.amp.openapi.session.AgentEventJson agentEventJson;

    public OpenAiCompatController(
            CatalogRuntimeService catalogRuntime,
            AgentInstanceCache instanceCache,
            PlatformMetrics metrics,
            ObjectMapper objectMapper,
            com.sinosoft.amp.openapi.session.SessionEventHub sessionHub,
            com.sinosoft.amp.openapi.session.SessionRunTracker runTracker,
            com.sinosoft.amp.openapi.governance.ApprovalInbox approvalInbox,
            com.sinosoft.amp.openapi.session.AgentEventJson agentEventJson) {
        this.catalogRuntime = catalogRuntime;
        this.instanceCache = instanceCache;
        this.metrics = metrics;
        this.objectMapper = objectMapper;
        this.sessionHub = sessionHub;
        this.runTracker = runTracker;
        this.approvalInbox = approvalInbox;
        this.agentEventJson = agentEventJson;
    }

    @PostMapping("/completions")
    public Object completions(
            @RequestBody OpenAiChatCompletionRequest request,
            @RequestHeader(value = "X-User-Id", required = false) String headerUserId,
            @RequestHeader(value = "X-Session-Id", required = false) String headerSessionId) {
        String agentId = resolveAgentId(request);
        String prompt = requireLastUserText(request);
        String userId = resolveUserId(request, headerUserId);
        String sessionId = resolveSessionId(headerSessionId);
        if (request != null && Boolean.TRUE.equals(request.stream())) {
            return streamCompletion(agentId, userId, sessionId, prompt);
        }
        return complete(agentId, userId, sessionId, prompt);
    }

    // ---------- 纯映射（可单测） ----------

    /** model 字段优先，否则 agentId 别名字段；空则 model_not_found。 */
    public static String resolveAgentId(OpenAiChatCompletionRequest request) {
        if (request == null) {
            throw new CatalogException("MODEL_NOT_FOUND", "model is required");
        }
        String id = request.model();
        if (id == null || id.isBlank()) {
            id = request.agentId();
        }
        if (id == null || id.isBlank()) {
            throw new CatalogException("MODEL_NOT_FOUND", "model is required");
        }
        return id.trim();
    }

    /** 从 messages 取最后一条 role=user 的文本；content 支持 string 或 parts 数组。 */
    public static String extractLastUserText(List<OpenAiMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return null;
        }
        for (int i = messages.size() - 1; i >= 0; i--) {
            OpenAiMessage m = messages.get(i);
            if (m != null && m.role() != null && "user".equalsIgnoreCase(m.role().trim())) {
                return contentToText(m.content());
            }
        }
        return null;
    }

    public static String contentToText(Object content) {
        if (content == null) {
            return "";
        }
        if (content instanceof String s) {
            return s;
        }
        if (content instanceof List<?> parts) {
            StringBuilder sb = new StringBuilder();
            for (Object part : parts) {
                if (part instanceof Map<?, ?> m) {
                    Object t = m.get("text");
                    if (t != null) {
                        sb.append(t);
                    }
                } else if (part instanceof CharSequence cs) {
                    sb.append(cs);
                }
            }
            return sb.toString();
        }
        if (content instanceof Map<?, ?> m) {
            Object t = m.get("text");
            return t == null ? String.valueOf(content) : String.valueOf(t);
        }
        return String.valueOf(content);
    }

    public static String resolveUserId(OpenAiChatCompletionRequest request, String headerUserId) {
        if (headerUserId != null && !headerUserId.isBlank()) {
            return headerUserId.trim();
        }
        if (request != null && request.user() != null && !request.user().isBlank()) {
            return request.user().trim();
        }
        return DEFAULT_USER;
    }

    public static String resolveSessionId(String headerSessionId) {
        if (headerSessionId != null && !headerSessionId.isBlank()) {
            return headerSessionId.trim();
        }
        return "s" + UUID.randomUUID().toString().replace("-", "");
    }

    public static String newCompletionId() {
        return "chatcmpl-" + UUID.randomUUID().toString().replace("-", "");
    }

    public static Map<String, Object> buildChatCompletion(
            String id,
            String model,
            long created,
            String content,
            int promptTokens,
            int completionTokens) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("id", id);
        root.put("object", "chat.completion");
        root.put("created", created);
        root.put("model", model);
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", "assistant");
        message.put("content", content == null ? "" : content);
        Map<String, Object> choice = new LinkedHashMap<>();
        choice.put("index", 0);
        choice.put("message", message);
        choice.put("finish_reason", "stop");
        root.put("choices", List.of(choice));
        Map<String, Object> usage = new LinkedHashMap<>();
        usage.put("prompt_tokens", promptTokens);
        usage.put("completion_tokens", completionTokens);
        usage.put("total_tokens", promptTokens + completionTokens);
        root.put("usage", usage);
        return root;
    }

    public static Map<String, Object> buildChunk(
            String id,
            String model,
            long created,
            Map<String, Object> delta,
            String finishReason) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("id", id);
        root.put("object", "chat.completion.chunk");
        root.put("created", created);
        root.put("model", model);
        Map<String, Object> choice = new LinkedHashMap<>();
        choice.put("index", 0);
        choice.put("delta", delta == null ? Map.of() : delta);
        choice.put("finish_reason", finishReason);
        root.put("choices", List.of(choice));
        return root;
    }

    public static Map<String, Object> buildModelNotFound(String model) {
        return buildOpenAiError(
                "model_not_found",
                "The model `" + (model == null ? "" : model)
                        + "` does not exist or you do not have access to it.",
                "invalid_request_error",
                "model");
    }

    public static Map<String, Object> buildOpenAiError(
            String code, String message, String type, String param) {
        Map<String, Object> err = new LinkedHashMap<>();
        err.put("message", message == null ? "" : message);
        err.put("type", type == null ? "invalid_request_error" : type);
        if (param != null && !param.isBlank()) {
            err.put("param", param);
        }
        err.put("code", code == null ? "invalid_request_error" : code);
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("error", err);
        return root;
    }

    /** SSE 行帧：data: {json}\n\n */
    public static String toSseData(String json) {
        return "data: " + (json == null ? "" : json) + "\n\n";
    }

    // ---------- 内部执行 ----------

    private String requireLastUserText(OpenAiChatCompletionRequest request) {
        String text = extractLastUserText(request == null ? null : request.messages());
        if (text == null || text.isBlank()) {
            throw new CatalogException("VALIDATION_FAILED", "messages must include a non-empty user message");
        }
        return text;
    }

    private Map<String, Object> complete(String agentId, String userId, String sessionId, String prompt) {
        long start = System.currentTimeMillis();
        String tenant = AuthContext.requireTenantId();
        AuthContext.requireAgent(agentId);
        RuntimeContext ctx = SessionContext.require(tenant, userId, sessionId);
        AgentInstance instance = catalogRuntime.resolve(tenant, agentId);
        runTracker.register(sessionId,
                new com.sinosoft.amp.openapi.session.SessionRunTracker.SessionMeta(tenant, agentId, userId));
        runTracker.begin(sessionId);
        RequireUserConfirmEvent pause;
        Map<String, Object> out;
        try (AgentInstanceCache.Lease lease = instanceCache.lease(instance)) {
            List<io.agentscope.core.event.AgentEvent> events = lease.agent()
                    .streamEvents(List.of(new UserMessage(prompt)), ctx)
                    .collectList()
                    .block();
            for (io.agentscope.core.event.AgentEvent event : events) {
                approvalInbox.capture(tenant, agentId, userId, sessionId, event);
                sessionHub.publish(sessionId, event.getType().name(), agentEventJson.of(event));
            }
            sessionHub.complete(sessionId);
            pause = lastPause(events);
            Msg result = lastResultMsg(events);
            String text = result == null ? "" : result.getTextContent();
            long ms = System.currentTimeMillis() - start;
            int promptTokens = metrics.estimateTokens(prompt);
            int completionTokens = metrics.estimateTokens(text);
            metrics.recordChat(agentId, ms, false);
            metrics.recordTokens(agentId, promptTokens, completionTokens);
            log.info("openai.chat.done agentId={} sessionId={} durationMs={} promptChars={} replyChars={} awaitConfirm={}",
                    agentId, sessionId, ms, prompt == null ? 0 : prompt.length(),
                    text == null ? 0 : text.length(), pause != null);
            out = buildChatCompletion(
                    newCompletionId(),
                    agentId,
                    Instant.now().getEpochSecond(),
                    text,
                    promptTokens,
                    completionTokens);
        } catch (RuntimeException e) {
            metrics.recordChat(agentId, System.currentTimeMillis() - start, true);
            throw e;
        } finally {
            runTracker.end(sessionId);
        }
        if (pause != null) {
            metrics.recordHitl(false);
            throw new CatalogException("HITL_CONFIRM_REQUIRED",
                    "tool call(s) await confirmation: use platform POST .../sessions/{sid}/confirm");
        }
        return out;
    }

    private static RequireUserConfirmEvent lastPause(List<io.agentscope.core.event.AgentEvent> events) {
        if (events == null) {
            return null;
        }
        RequireUserConfirmEvent pause = null;
        for (io.agentscope.core.event.AgentEvent event : events) {
            if (event instanceof RequireUserConfirmEvent confirm) {
                pause = confirm;
            }
        }
        return pause;
    }

    private static Msg lastResultMsg(List<io.agentscope.core.event.AgentEvent> events) {
        if (events == null) {
            return null;
        }
        Msg result = null;
        for (io.agentscope.core.event.AgentEvent event : events) {
            if (event instanceof io.agentscope.core.event.AgentResultEvent agentResult
                    && agentResult.getResult() != null) {
                result = agentResult.getResult();
            }
        }
        return result;
    }

    private SseEmitter streamCompletion(String agentId, String userId, String sessionId, String prompt) {
        long start = System.currentTimeMillis();
        String tenant = AuthContext.requireTenantId();
        AuthContext.requireAgent(agentId);
        RuntimeContext ctx = SessionContext.require(tenant, userId, sessionId);
        AgentInstance instance = catalogRuntime.resolve(tenant, agentId);

        SseEmitter emitter = new SseEmitter(0L);
        String id = newCompletionId();
        long created = Instant.now().getEpochSecond();
        AgentInstanceCache.Lease lease = instanceCache.lease(instance);
        AtomicBoolean released = new AtomicBoolean(false);
        AtomicBoolean sawPause = new AtomicBoolean(false);
        AtomicReference<StringBuilder> reply = new AtomicReference<>(new StringBuilder());
        runTracker.register(sessionId,
                new com.sinosoft.amp.openapi.session.SessionRunTracker.SessionMeta(tenant, agentId, userId));
        runTracker.begin(sessionId);

        Runnable releaseOnce = () -> {
            if (released.compareAndSet(false, true)) {
                runTracker.end(sessionId);
                sessionHub.complete(sessionId);
                lease.close();
                long ms = System.currentTimeMillis() - start;
                int promptTokens = metrics.estimateTokens(prompt);
                int completionTokens = metrics.estimateTokens(reply.get().toString());
                metrics.recordChat(agentId, ms, false);
                metrics.recordTokens(agentId, promptTokens, completionTokens);
                log.info("openai.stream.done agentId={} sessionId={} durationMs={}",
                        agentId, sessionId, ms);
            }
        };

        try {
            sendJson(emitter, buildChunk(id, agentId, created,
                    Map.of("role", "assistant"), null));
            lease.agent().streamEvents(List.of(new UserMessage(prompt)), ctx)
                    .subscribe(
                            event -> {
                                if (event instanceof RequireUserConfirmEvent) {
                                    sawPause.set(true);
                                    metrics.recordHitl(false);
                                }
                                approvalInbox.capture(tenant, agentId, userId, sessionId, event);
                                sessionHub.publish(sessionId, event.getType().name(), agentEventJson.of(event));
                                if (event instanceof TextBlockDeltaEvent delta) {
                                    String piece = delta.getDelta();
                                    if (piece != null && !piece.isEmpty()) {
                                        reply.get().append(piece);
                                        sendJson(emitter, buildChunk(id, agentId, created,
                                                Map.of("content", piece), null));
                                    }
                                }
                            },
                            error -> {
                                log.error("openai.stream failed agentId={}", agentId, error);
                                try {
                                    sendJson(emitter, buildOpenAiError(
                                            "stream_error",
                                            error.getMessage(),
                                            "server_error",
                                            null));
                                } catch (Exception ignored) {
                                    // client gone
                                }
                                emitter.completeWithError(error);
                                metrics.recordChat(agentId, System.currentTimeMillis() - start, true);
                                releaseOnce.run();
                            },
                            () -> {
                                try {
                                    if (sawPause.get()) {
                                        // 暂停（待人工确认）不再以空内容假装正常结束
                                        sendJson(emitter, buildOpenAiError(
                                                "hitl_confirm_required",
                                                "tool call(s) await confirmation; use platform confirm endpoint",
                                                "invalid_request_error",
                                                null));
                                    } else {
                                        sendJson(emitter, buildChunk(id, agentId, created, Map.of(), "stop"));
                                    }
                                    emitter.send(SseEmitter.event()
                                            .data("[DONE]", MediaType.TEXT_PLAIN));
                                } catch (Exception ignored) {
                                    // client gone
                                }
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

    private void sendJson(SseEmitter emitter, Map<String, Object> payload) {
        try {
            String json = objectMapper.writeValueAsString(payload);
            emitter.send(SseEmitter.event().data(json, MediaType.APPLICATION_JSON));
        } catch (Exception e) {
            throw new IllegalStateException("sse send failed: " + e.getMessage(), e);
        }
    }

    // ---------- OpenAI 风格错误 ----------

    @ExceptionHandler(CatalogException.class)
    public ResponseEntity<Map<String, Object>> handleCatalog(CatalogException e) {
        String code = e.code();
        if ("MODEL_NOT_FOUND".equals(code) || "AGENT_NOT_FOUND".equals(code)) {
            String model = extractModelFromMessage(e.getMessage());
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(buildModelNotFound(model));
        }
        if ("VALIDATION_FAILED".equals(code)) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
                    buildOpenAiError("invalid_request_error", e.getMessage(), "invalid_request_error", "messages"));
        }
        HttpStatus status = switch (code) {
            case "NO_RELEASE", "VERSION_NOT_FOUND", "VERSION_ARCHIVED" -> HttpStatus.NOT_FOUND;
            case "QUOTA_EXCEEDED" -> HttpStatus.TOO_MANY_REQUESTS;
            case "AGENT_DISABLED", "VERSION_DRAINING", "APPROVAL_ALREADY_RESOLVED" -> HttpStatus.CONFLICT;
            case "HITL_CONFIRM_REQUIRED" -> HttpStatus.CONFLICT;
            default -> HttpStatus.BAD_REQUEST;
        };
        return ResponseEntity.status(status).body(
                buildOpenAiError(code, e.getMessage(), "invalid_request_error", null));
    }

    @ExceptionHandler(SessionContextException.class)
    public ResponseEntity<Map<String, Object>> handleSession(SessionContextException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
                buildOpenAiError("invalid_request_error", e.getMessage(), "invalid_request_error", null));
    }

    @ExceptionHandler(UnauthorizedException.class)
    public ResponseEntity<Map<String, Object>> handleUnauthorized(UnauthorizedException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(
                buildOpenAiError("invalid_api_key", e.getMessage(), "invalid_request_error", null));
    }

    @ExceptionHandler(org.springframework.web.bind.MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(
            org.springframework.web.bind.MethodArgumentNotValidException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
                buildOpenAiError("invalid_request_error", e.getMessage(), "invalid_request_error", null));
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<Map<String, Object>> handleRuntime(RuntimeException e) {
        if (e instanceof CatalogException ce) {
            return handleCatalog(ce);
        }
        if (e instanceof UnauthorizedException ue) {
            return handleUnauthorized(ue);
        }
        if (e instanceof SessionContextException se) {
            return handleSession(se);
        }
        log.error("openai compat request failed", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                buildOpenAiError("internal_error", e.getMessage(), "server_error", null));
    }

    private static String extractModelFromMessage(String message) {
        if (message == null) {
            return "";
        }
        int idx = message.indexOf(':');
        if (idx >= 0 && idx + 1 < message.length()) {
            return message.substring(idx + 1).trim();
        }
        return message;
    }

    // ---------- DTO ----------

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record OpenAiChatCompletionRequest(
            String model,
            String agentId,
            List<OpenAiMessage> messages,
            Boolean stream,
            String user
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record OpenAiMessage(String role, Object content) {
    }
}
