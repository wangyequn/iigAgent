package com.example.agent.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.example.agent.runtime.AgentSessions;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.RequireUserConfirmEvent;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.harness.agent.HarnessAgent;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP 契约子集 + HITL content 回填约定（MockMvc，不启 Spring 上下文 / 不连 Redis）。
 */
class ChatHttpContractTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void healthUp() throws Exception {
        MockMvc mvc = mvcWith(agentReturning());
        mvc.perform(get("/api/v1/health/demo"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void emptySessionReturns400() throws Exception {
        MockMvc mvc = mvcWith(agentReturning());
        mvc.perform(post("/api/v1/agents/demo/messages")
                        .contentType("application/json")
                        .content("""
                                {"tenantId":"acme","userId":"u1","sessionId":"","message":"hi"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SESSION_CONTEXT_INVALID"));
    }

    @Test
    void confirmRebuildsToolUseBlockWithContent() throws Exception {
        HarnessAgent agent = mock(HarnessAgent.class);
        AgentResultEvent result = new AgentResultEvent(
                UserMessage.builder().name("demo").textContent("已写入").build());
        when(agent.streamEvents(anyList(), any(RuntimeContext.class)))
                .thenReturn(Flux.just(result));

        MockMvc mvc = mvcWith(agent);
        mvc.perform(post("/api/v1/agents/demo/sessions/sess-1/confirm")
                        .contentType("application/json")
                        .content("""
                                {
                                  "tenantId":"acme","userId":"u1",
                                  "note":"同意",
                                  "toolCalls":[{
                                    "toolCallId":"call_1",
                                    "toolName":"dangerous_write",
                                    "input":{"target":"demo"},
                                    "confirmed":true
                                  }]
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.awaitConfirm").value(false))
                .andExpect(jsonPath("$.reply").value("已写入"));

        @SuppressWarnings({"unchecked", "rawtypes"})
        ArgumentCaptor<List> captor = ArgumentCaptor.forClass(List.class);
        verify(agent).streamEvents(captor.capture(), any(RuntimeContext.class));
        UserMessage resume = (UserMessage) captor.getValue().get(0);
        assertEquals("同意", resume.getTextContent());
        assertNotNull(resume.getMetadata().get("confirmResults"));
    }

    @Test
    void pauseReturnsPendingTools() throws Exception {
        HarnessAgent agent = mock(HarnessAgent.class);
        ToolUseBlock pending = ToolUseBlock.builder()
                .id("call_0")
                .name("dangerous_write")
                .input(Map.of("target", "demo"))
                .content("{\"target\":\"demo\"}")
                .build();
        RequireUserConfirmEvent pause = new RequireUserConfirmEvent("reply-1", List.of(pending));
        when(agent.streamEvents(anyList(), any(RuntimeContext.class)))
                .thenReturn(Flux.just(pause));

        MockMvc mvc = mvcWith(agent);
        mvc.perform(post("/api/v1/agents/demo/messages")
                        .contentType("application/json")
                        .content("""
                                {"tenantId":"acme","userId":"u1","sessionId":"s-p","message":"写入"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.awaitConfirm").value(true))
                .andExpect(jsonPath("$.pendingTools[0].toolName").value("dangerous_write"));
    }

    @Test
    void streamPathIsColonMessagesStream() throws Exception {
        // 路径契约：POST .../messages:stream（冒号），防止抄成 messages/stream
        MockMvc mvc = mvcWith(agentReturning());
        mvc.perform(post("/api/v1/agents/demo/messages:stream")
                        .contentType("application/json")
                        .content("""
                                {"tenantId":"acme","userId":"u1","sessionId":"s1","message":"hi"}
                                """))
                .andExpect(status().isOk());
    }

    /** content 必须是入参 JSON 串（ToolExecutor schema 校验），只设 input 会空回复。 */
    @Test
    void toolUseBlockContentMustBeJsonString() {
        Map<String, Object> input = Map.of("target", "demo");
        ToolUseBlock block = ToolUseBlock.builder()
                .id("call_1")
                .name("dangerous_write")
                .input(input)
                .content("{\"target\":\"demo\"}")
                .build();
        assertEquals("{\"target\":\"demo\"}", block.getContent());
    }

    private HarnessAgent agentReturning() {
        HarnessAgent agent = mock(HarnessAgent.class);
        when(agent.streamEvents(anyList(), any(RuntimeContext.class)))
                .thenReturn(Flux.empty());
        return agent;
    }

    private MockMvc mvcWith(HarnessAgent agent) {
        AgentSessions sessions = mock(AgentSessions.class);
        when(sessions.get(eq("demo"))).thenReturn(agent);
        return MockMvcBuilders
                .standaloneSetup(new ChatController(sessions, mapper))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }
}
