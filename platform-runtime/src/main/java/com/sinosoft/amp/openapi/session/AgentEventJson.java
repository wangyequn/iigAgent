package com.sinosoft.amp.openapi.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.event.AgentEndEvent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentStartEvent;
import io.agentscope.core.event.AllToolsDeniedEvent;
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
import io.agentscope.core.message.ToolUseBlock;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AgentEvent → SSE data JSON（完整载荷：delta/replyId/blockId/toolCallId/state 等）。
 * 流式触发、只读订阅与唤醒恢复三条路径共用同一序列化，保证事件形状一致。
 */
@Component
public class AgentEventJson {

    private final ObjectMapper objectMapper;

    public AgentEventJson(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String of(AgentEvent event) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("type", event.getType().name());
            payload.put("id", event.getId());
            payload.put("source", event.getSource());
            if (event instanceof AgentStartEvent start) {
                put(payload, "replyId", start.getReplyId());
                put(payload, "sessionId", start.getSessionId());
                put(payload, "name", start.getName());
            } else if (event instanceof AgentEndEvent end) {
                put(payload, "replyId", end.getReplyId());
            } else if (event instanceof TextBlockStartEvent start) {
                put(payload, "replyId", start.getReplyId());
                put(payload, "blockId", start.getBlockId());
            } else if (event instanceof TextBlockEndEvent end) {
                put(payload, "replyId", end.getReplyId());
                put(payload, "blockId", end.getBlockId());
            } else if (event instanceof TextBlockDeltaEvent delta) {
                put(payload, "replyId", delta.getReplyId());
                put(payload, "blockId", delta.getBlockId());
                put(payload, "delta", delta.getDelta());
            } else if (event instanceof ThinkingBlockStartEvent start) {
                put(payload, "replyId", start.getReplyId());
                put(payload, "blockId", start.getBlockId());
            } else if (event instanceof ThinkingBlockEndEvent end) {
                put(payload, "replyId", end.getReplyId());
                put(payload, "blockId", end.getBlockId());
            } else if (event instanceof ThinkingBlockDeltaEvent delta) {
                put(payload, "replyId", delta.getReplyId());
                put(payload, "blockId", delta.getBlockId());
                put(payload, "delta", delta.getDelta());
            } else if (event instanceof ToolCallStartEvent start) {
                put(payload, "replyId", start.getReplyId());
                put(payload, "toolCallId", start.getToolCallId());
                put(payload, "toolCallName", start.getToolCallName());
            } else if (event instanceof ToolCallDeltaEvent delta) {
                put(payload, "replyId", delta.getReplyId());
                put(payload, "toolCallId", delta.getToolCallId());
                put(payload, "toolCallName", delta.getToolCallName());
                put(payload, "delta", delta.getDelta());
            } else if (event instanceof ToolCallEndEvent end) {
                put(payload, "replyId", end.getReplyId());
                put(payload, "toolCallId", end.getToolCallId());
                put(payload, "toolCallName", end.getToolCallName());
            } else if (event instanceof ToolResultStartEvent start) {
                put(payload, "replyId", start.getReplyId());
                put(payload, "toolCallId", start.getToolCallId());
                put(payload, "toolCallName", start.getToolCallName());
            } else if (event instanceof ToolResultTextDeltaEvent delta) {
                put(payload, "replyId", delta.getReplyId());
                put(payload, "toolCallId", delta.getToolCallId());
                put(payload, "toolCallName", delta.getToolCallName());
                put(payload, "delta", delta.getDelta());
            } else if (event instanceof ToolResultEndEvent end) {
                put(payload, "replyId", end.getReplyId());
                put(payload, "toolCallId", end.getToolCallId());
                put(payload, "toolCallName", end.getToolCallName());
                put(payload, "state", end.getState() == null ? null : end.getState().name());
            } else if (event instanceof ModelCallEndEvent end) {
                put(payload, "replyId", end.getReplyId());
                if (end.getUsage() != null) {
                    Map<String, Object> usage = new LinkedHashMap<>();
                    usage.put("inputTokens", end.getUsage().getInputTokens());
                    usage.put("outputTokens", end.getUsage().getOutputTokens());
                    payload.put("usage", usage);
                }
            } else if (event instanceof ExceedMaxItersEvent exceed) {
                put(payload, "replyId", exceed.getReplyId());
                payload.put("maxIters", exceed.getMaxIters());
                payload.put("currentIter", exceed.getCurrentIter());
            } else if (event instanceof AllToolsDeniedEvent denied) {
                payload.put("toolCalls", toolCallsJson(denied.getDeniedToolCalls()));
            } else if (event instanceof HintBlockEvent hint) {
                put(payload, "replyId", hint.getReplyId());
                put(payload, "blockId", hint.getBlockId());
                put(payload, "hint", hint.getHint());
                put(payload, "hintSource", hint.getHintSource());
            } else if (event instanceof CustomEvent custom) {
                put(payload, "name", custom.getName());
                payload.put("value", custom.getValue());
            } else if (event instanceof RequestStopEvent stop) {
                put(payload, "reason", stop.getReason());
            } else if (event instanceof RequireExternalExecutionEvent ext) {
                put(payload, "replyId", ext.getReplyId());
                payload.put("toolCalls", toolCallsJson(ext.getToolCalls()));
            }
            if (event instanceof RequireUserConfirmEvent confirm) {
                payload.put("replyId", confirm.getReplyId());
                payload.put("toolCalls", toolCallsJson(confirm.getToolCalls()));
            }
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            return "{\"type\":\"" + event.getType().name() + "\"}";
        }
    }

    private List<Map<String, Object>> toolCallsJson(List<ToolUseBlock> blocks) {
        List<Map<String, Object>> calls = new ArrayList<>();
        if (blocks != null) {
            for (ToolUseBlock b : blocks) {
                Map<String, Object> c = new LinkedHashMap<>();
                c.put("toolCallId", b.getId());
                c.put("toolName", b.getName());
                c.put("input", b.getInput());
                calls.add(c);
            }
        }
        return calls;
    }

    private static void put(Map<String, Object> payload, String key, Object value) {
        if (value != null) {
            payload.put(key, value);
        }
    }
}
