package com.example.agent.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.example.agent.runtime.AgentSessions;
import com.example.agent.session.SessionContext;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.RequireUserConfirmEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.harness.agent.HarnessAgent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

/**
 * Chat 金路径三接口（与中台契约对齐的最小子集）：
 * <ul>
 *   <li>{@code POST .../messages} 非流式</li>
 *   <li>{@code POST .../messages:stream} SSE（注意路径冒号）</li>
 *   <li>{@code POST .../sessions/{sid}/confirm} HITL 确认（必须回填 ToolUseBlock.content）</li>
 * </ul>
 */
@RestController
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    private final AgentSessions sessions;
    private final AgentEventJson eventJson;
    private final ObjectMapper mapper;
    private final ExecutorService sseExecutor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "starter-sse");
        t.setDaemon(true);
        return t;
    });

    public ChatController(AgentSessions sessions, ObjectMapper mapper) {
        this.sessions = sessions;
        this.mapper = mapper;
        this.eventJson = new AgentEventJson(mapper);
    }

    @GetMapping("/api/v1/health/demo")
    public Map<String, Object> health() {
        return Map.of("status", "UP", "service", "amp-starter");
    }

    // ---------- DTO ----------

    public record ChatRequest(String tenantId, String userId, String sessionId, String message) {
    }

    public record ToolCallDecision(String toolCallId, String toolName, Map<String, Object> input,
                                   Boolean confirmed) {
    }

    public record ConfirmRequest(String tenantId, String userId, String note,
                                 Boolean confirmed, List<ToolCallDecision> toolCalls) {
    }

    public record ChatReply(String agentId, String sessionId, String reply, boolean awaitConfirm,
                            List<Map<String, Object>> pendingTools, String generateReason) {
    }

    // ---------- messages ----------

    @PostMapping(value = "/api/v1/agents/{agentId}/messages", produces = MediaType.APPLICATION_JSON_VALUE)
    public ChatReply messages(@PathVariable String agentId, @RequestBody ChatRequest request) {
        SessionContext.SessionKey key = SessionContext.require(
                request.tenantId(), request.userId(), request.sessionId());
        RuntimeContext ctx = SessionContext.toRuntimeContext(key);
        HarnessAgent agent = sessions.get(agentId);

        List<AgentEvent> events = agent
                .streamEvents(List.of(new UserMessage(request.message() == null ? "" : request.message())), ctx)
                .collectList()
                .block();
        return toReply(agentId, key.sessionId(), events);
    }

    // ---------- messages:stream（路径带冒号，EventSource 无法 POST，需 fetch） ----------

    @PostMapping(value = "/api/v1/agents/{agentId}/messages:stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable String agentId, @RequestBody ChatRequest request) {
        SessionContext.SessionKey key = SessionContext.require(
                request.tenantId(), request.userId(), request.sessionId());
        RuntimeContext ctx = SessionContext.toRuntimeContext(key);
        HarnessAgent agent = sessions.get(agentId);

        SseEmitter emitter = new SseEmitter(0L);
        sseExecutor.execute(() -> {
            try {
                agent.streamEvents(List.of(new UserMessage(request.message() == null ? "" : request.message())), ctx)
                        .doOnNext(event -> send(emitter, event))
                        .doOnComplete(emitter::complete)
                        .doOnError(emitter::completeWithError)
                        .subscribe();
            } catch (Exception e) {
                log.error("stream failed agentId={}", agentId, e);
                emitter.completeWithError(e);
            }
        });
        return emitter;
    }

    // ---------- confirm（HITL） ----------

    /**
     * HITL 确认。恢复执行时必须重建 {@link ToolUseBlock} 并回填 <b>content</b>
     * （入参 JSON 串）——只设 input 会报 {@code argument "content" is null}，
     * 工具 ERROR → 模型重试 → 回复恒空。
     */
    @PostMapping(value = "/api/v1/agents/{agentId}/sessions/{sessionId}/confirm",
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ChatReply confirm(
            @PathVariable String agentId,
            @PathVariable String sessionId,
            @RequestBody ConfirmRequest request) {
        SessionContext.SessionKey key = SessionContext.require(
                request.tenantId(), request.userId(), sessionId);
        RuntimeContext ctx = SessionContext.toRuntimeContext(key);
        HarnessAgent agent = sessions.get(agentId);

        List<Map<String, Object>> confirmResults = new ArrayList<>();
        if (request.toolCalls() != null) {
            for (ToolCallDecision d : request.toolCalls()) {
                Map<String, Object> input = d.input() == null ? Map.of() : d.input();
                // ★ content = 入参 JSON 串，ToolExecutor 做 schema 校验用
                ToolUseBlock block = ToolUseBlock.builder()
                        .id(d.toolCallId())
                        .name(d.toolName())
                        .input(input)
                        .content(toJson(input))
                        .build();
                boolean confirmed = d.confirmed() == null || Boolean.TRUE.equals(d.confirmed());
                Map<String, Object> one = new LinkedHashMap<>();
                one.put("toolCallId", block.getId());
                one.put("toolName", block.getName());
                one.put("confirmed", confirmed);
                confirmResults.add(one);
            }
        }
        if (confirmResults.isEmpty() && request.confirmed() != null) {
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("confirmed", Boolean.TRUE.equals(request.confirmed()));
            confirmResults.add(one);
        }

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("confirmResults", confirmResults);
        String text = request.note() != null && !request.note().isBlank()
                ? request.note()
                : "用户已批准该工具调用";
        UserMessage resume = UserMessage.builder()
                .name("user")
                .textContent(text)
                .metadata(metadata)
                .build();

        List<AgentEvent> events = agent.streamEvents(List.of(resume), ctx).collectList().block();
        return toReply(agentId, sessionId, events);
    }

    // ---------- helpers ----------

    private void send(SseEmitter emitter, AgentEvent event) {
        try {
            emitter.send(SseEmitter.event().name(event.getType().name()).data(eventJson.of(event)));
        } catch (IOException e) {
            log.debug("sse send failed: {}", e.toString());
        }
    }

    private ChatReply toReply(String agentId, String sessionId, List<AgentEvent> events) {
        List<Map<String, Object>> pending = new ArrayList<>();
        String text = "";
        String reason = "";
        boolean awaitConfirm = false;
        RequireUserConfirmEvent pause = lastPause(events);
        if (pause != null && pause.getToolCalls() != null && !pause.getToolCalls().isEmpty()) {
            awaitConfirm = true;
            for (ToolUseBlock block : pause.getToolCalls()) {
                Map<String, Object> tool = new LinkedHashMap<>();
                tool.put("toolCallId", block.getId());
                tool.put("toolName", block.getName());
                // 前端回传时必须 JSON.parse 后放进 toolCalls[].input
                tool.put("toolInput", toJson(block.getInput()));
                pending.add(tool);
            }
        }
        Msg result = lastResultMsg(events);
        if (result != null) {
            text = result.getTextContent() == null ? "" : result.getTextContent();
            if (result.getGenerateReason() != null) {
                reason = result.getGenerateReason().name();
            }
        }
        return new ChatReply(agentId, sessionId, text, awaitConfirm, pending, reason);
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

    private String toJson(Object o) {
        try {
            return o == null ? "{}" : mapper.writeValueAsString(o);
        } catch (Exception e) {
            return "{}";
        }
    }
}
