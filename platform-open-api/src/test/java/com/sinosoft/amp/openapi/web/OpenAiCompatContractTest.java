package com.sinosoft.amp.openapi.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sinosoft.amp.openapi.agent.AgentInstance;
import com.sinosoft.amp.openapi.agent.AgentInstanceCache;
import com.sinosoft.amp.openapi.agent.CatalogRuntimeService;
import com.sinosoft.amp.openapi.catalog.CatalogException;
import com.sinosoft.amp.openapi.ops.PlatformMetrics;
import com.sinosoft.amp.openapi.ops.RuntimeConfigService;
import com.sinosoft.amp.openapi.ops.OpsProperties;
import com.sinosoft.amp.openapi.security.AuthContext;
import com.sinosoft.amp.openapi.security.AuthProperties;
import io.agentscope.core.message.Msg;
import io.agentscope.harness.agent.HarnessAgent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * OpenAI 兼容入口契约测试：纯映射 + MockMvc（mock 目录/Agent），不启 Spring 全上下文。
 */
class OpenAiCompatContractTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private CatalogRuntimeService catalogRuntime;
    private AgentInstanceCache instanceCache;
    private PlatformMetrics metrics;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        catalogRuntime = mock(CatalogRuntimeService.class);
        instanceCache = new AgentInstanceCache(
                (tenant, agentId, version, modelId, sysPrompt, workspace) -> mock(HarnessAgent.class), 10, 1000);
        metrics = new PlatformMetrics(
                new SimpleMeterRegistry(),
                new RuntimeConfigService(new OpsProperties(true, 120, 4, 100)));
        OpenAiCompatController controller =
                new OpenAiCompatController(catalogRuntime, instanceCache, metrics, mapper,
                        new com.sinosoft.amp.openapi.session.SessionEventHub(
                                new com.sinosoft.amp.openapi.session.SseReplayBuffer(30_000)),
                        new com.sinosoft.amp.openapi.session.SessionRunTracker(),
                        new com.sinosoft.amp.openapi.governance.ApprovalInbox(
                                mock(com.sinosoft.amp.openapi.governance.GovernanceService.class), mapper),
                        new com.sinosoft.amp.openapi.session.AgentEventJson(mapper));
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
        AuthContext.set(new AuthProperties.KeyEntry("open-key", "acme", List.of(), List.of("open")));
    }

    @AfterEach
    void tearDown() {
        AuthContext.clear();
    }

    // ---------- 映射 ----------

    @Test
    void extractLastUserTextPicksLastUserMessage() {
        List<OpenAiCompatController.OpenAiMessage> messages = List.of(
                new OpenAiCompatController.OpenAiMessage("system", "you are helpful"),
                new OpenAiCompatController.OpenAiMessage("user", "first"),
                new OpenAiCompatController.OpenAiMessage("assistant", "ok"),
                new OpenAiCompatController.OpenAiMessage("user", "second"));
        assertEquals("second", OpenAiCompatController.extractLastUserText(messages));
    }

    @Test
    void extractLastUserTextSupportsContentParts() {
        Map<String, Object> part = new LinkedHashMap<>();
        part.put("type", "text");
        part.put("text", "hello parts");
        List<OpenAiCompatController.OpenAiMessage> messages = List.of(
                new OpenAiCompatController.OpenAiMessage("user", List.of(part)));
        assertEquals("hello parts", OpenAiCompatController.extractLastUserText(messages));
    }

    @Test
    void extractLastUserTextNullWhenNoUser() {
        assertNull(OpenAiCompatController.extractLastUserText(null));
        assertNull(OpenAiCompatController.extractLastUserText(List.of()));
        assertNull(OpenAiCompatController.extractLastUserText(
                List.of(new OpenAiCompatController.OpenAiMessage("system", "s"))));
    }

    @Test
    void resolveAgentIdPrefersModelThenAgentId() {
        assertEquals("demo", OpenAiCompatController.resolveAgentId(
                new OpenAiCompatController.OpenAiChatCompletionRequest("demo", "other", null, false, null)));
        assertEquals("demo", OpenAiCompatController.resolveAgentId(
                new OpenAiCompatController.OpenAiChatCompletionRequest(null, "demo", null, false, null)));
        CatalogException ex = assertThrows(CatalogException.class, () ->
                OpenAiCompatController.resolveAgentId(
                        new OpenAiCompatController.OpenAiChatCompletionRequest(null, null, null, false, null)));
        assertEquals("MODEL_NOT_FOUND", ex.code());
    }

    @Test
    void resolveUserAndSessionDefaults() {
        assertEquals(OpenAiCompatController.DEFAULT_USER,
                OpenAiCompatController.resolveUserId(null, null));
        assertEquals("alice", OpenAiCompatController.resolveUserId(null, "alice"));
        assertEquals("bob", OpenAiCompatController.resolveUserId(
                new OpenAiCompatController.OpenAiChatCompletionRequest("demo", null, null, false, "bob"), null));
        String sid = OpenAiCompatController.resolveSessionId(null);
        assertTrue(sid.matches("[A-Za-z0-9_-]{1,128}"));
        assertEquals("keep", OpenAiCompatController.resolveSessionId("keep"));
    }

    @Test
    void chatCompletionShapeUsesOpenAiObjectAndUsage() {
        Map<String, Object> body = OpenAiCompatController.buildChatCompletion(
                "chatcmpl-1", "demo", 1700000000L, "hi", 3, 5);
        assertEquals("chatcmpl-1", body.get("id"));
        assertEquals("chat.completion", body.get("object"));
        assertEquals("demo", body.get("model"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> choices = (List<Map<String, Object>>) body.get("choices");
        assertEquals(1, choices.size());
        @SuppressWarnings("unchecked")
        Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
        assertEquals("assistant", message.get("role"));
        assertEquals("hi", message.get("content"));
        assertEquals("stop", choices.get(0).get("finish_reason"));
        @SuppressWarnings("unchecked")
        Map<String, Object> usage = (Map<String, Object>) body.get("usage");
        assertEquals(3, usage.get("prompt_tokens"));
        assertEquals(5, usage.get("completion_tokens"));
        assertEquals(8, usage.get("total_tokens"));
    }

    @Test
    void chunkShapeIsChatCompletionChunk() {
        Map<String, Object> chunk = OpenAiCompatController.buildChunk(
                "chatcmpl-1", "demo", 1700000000L, Map.of("content", "x"), null);
        assertEquals("chat.completion.chunk", chunk.get("object"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> choices = (List<Map<String, Object>>) chunk.get("choices");
        @SuppressWarnings("unchecked")
        Map<String, Object> delta = (Map<String, Object>) choices.get(0).get("delta");
        assertEquals("x", delta.get("content"));
        assertNull(choices.get(0).get("finish_reason"));
    }

    @Test
    void modelNotFoundStyleError() {
        Map<String, Object> body = OpenAiCompatController.buildModelNotFound("gpt-4o");
        @SuppressWarnings("unchecked")
        Map<String, Object> err = (Map<String, Object>) body.get("error");
        assertEquals("model_not_found", err.get("code"));
        assertEquals("model", err.get("param"));
        assertEquals("invalid_request_error", err.get("type"));
        assertTrue(String.valueOf(err.get("message")).contains("gpt-4o"));
    }

    @Test
    void sseDataAndDoneFrames() {
        assertEquals("data: {\"a\":1}\n\n", OpenAiCompatController.toSseData("{\"a\":1}"));
        assertEquals("data: [DONE]\n\n", OpenAiCompatController.DONE_FRAME);
    }

    // ---------- HTTP 契约 ----------

    @Test
    void missingModelReturnsModelNotFound() throws Exception {
        mockMvc.perform(post("/api/v1/chat/completions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"messages":[{"role":"user","content":"hi"}]}
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("model_not_found"))
                .andExpect(jsonPath("$.error.param").value("model"));
    }

    @Test
    void unknownModelReturnsModelNotFound() throws Exception {
        when(catalogRuntime.resolve("acme", "gpt-4o"))
                .thenThrow(new CatalogException("AGENT_NOT_FOUND", "agent not found: gpt-4o"));
        mockMvc.perform(post("/api/v1/chat/completions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"model":"gpt-4o","messages":[{"role":"user","content":"hi"}]}
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("model_not_found"));
    }

    @Test
    void missingUserMessageReturnsInvalidRequest() throws Exception {
        mockMvc.perform(post("/api/v1/chat/completions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"model":"demo","messages":[{"role":"system","content":"s"}]}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("invalid_request_error"));
    }

    @Test
    void nonStreamReturnsChatCompletion() throws Exception {
        HarnessAgent agent = mock(HarnessAgent.class);
        // complete() 已改走事件流（暂停检测），最终文本从 AgentResultEvent 取
        when(agent.streamEvents(anyList(), any(io.agentscope.core.agent.RuntimeContext.class)))
                .thenReturn(reactor.core.publisher.Flux.just(
                        new io.agentscope.core.event.AgentResultEvent(
                                io.agentscope.core.message.UserMessage.builder()
                                        .name("demo")
                                        .textContent("Hello from agent")
                                        .build())));
        AgentInstance instance = new AgentInstance("acme:demo:1", agent);
        when(catalogRuntime.resolve("acme", "demo")).thenReturn(instance);

        mockMvc.perform(post("/api/v1/chat/completions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Session-Id", "sess-1")
                        .content("""
                                {"model":"demo","stream":false,
                                 "messages":[{"role":"user","content":"hi"}]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.object").value("chat.completion"))
                .andExpect(jsonPath("$.model").value("demo"))
                .andExpect(jsonPath("$.choices[0].message.role").value("assistant"))
                .andExpect(jsonPath("$.choices[0].message.content").value("Hello from agent"))
                .andExpect(jsonPath("$.choices[0].finish_reason").value("stop"))
                .andExpect(jsonPath("$.usage.prompt_tokens").isNumber())
                .andExpect(jsonPath("$.usage.completion_tokens").isNumber())
                .andExpect(jsonPath("$.usage.total_tokens").isNumber())
                .andExpect(jsonPath("$.id").isString());
    }

    @Test
    void alsoMappedUnderV1ChatPath() throws Exception {
        mockMvc.perform(post("/v1/chat/completions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"messages":[{"role":"user","content":"hi"}]}
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("model_not_found"));
    }

    @Test
    void unknownJsonFieldsAreIgnored() throws Exception {
        when(catalogRuntime.resolve("acme", "demo"))
                .thenThrow(new CatalogException("AGENT_NOT_FOUND", "agent not found: demo"));
        mockMvc.perform(post("/api/v1/chat/completions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"model":"demo","temperature":0.2,"max_tokens":64,
                                 "messages":[{"role":"user","content":"hi","name":"n"}]}
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("model_not_found"));
    }

    @Test
    void v1ChatPathIsCoveredApiKeyAuthFilter() throws Exception {
        com.sinosoft.amp.openapi.security.ApiKeyAuthFilter filter =
                new com.sinosoft.amp.openapi.security.ApiKeyAuthFilter(
                        new AuthProperties(true, List.of(
                                new AuthProperties.KeyEntry("open-key", "acme", List.of("demo"), List.of("open")))));
        org.springframework.mock.web.MockHttpServletRequest req =
                new org.springframework.mock.web.MockHttpServletRequest("POST", "/v1/chat/completions");
        org.springframework.mock.web.MockHttpServletResponse res =
                new org.springframework.mock.web.MockHttpServletResponse();
        filter.doFilter(req, res, new org.springframework.mock.web.MockFilterChain());
        assertEquals(401, res.getStatus());
        assertTrue(res.getContentAsString().contains("MISSING_API_KEY"));

        org.springframework.mock.web.MockHttpServletRequest health =
                new org.springframework.mock.web.MockHttpServletRequest("GET", "/api/v1/health/ready");
        org.springframework.mock.web.MockHttpServletResponse healthRes =
                new org.springframework.mock.web.MockHttpServletResponse();
        final boolean[] passed = {false};
        filter.doFilter(health, healthRes, new org.springframework.mock.web.MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest r, jakarta.servlet.ServletResponse s) {
                passed[0] = true;
            }
        });
        assertTrue(passed[0]);
    }
}
