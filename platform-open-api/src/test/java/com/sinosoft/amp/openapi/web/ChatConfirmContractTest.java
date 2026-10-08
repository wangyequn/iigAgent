package com.sinosoft.amp.openapi.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sinosoft.amp.openapi.agent.AgentInstance;
import com.sinosoft.amp.openapi.agent.AgentInstanceCache;
import com.sinosoft.amp.openapi.agent.CatalogRuntimeService;
import com.sinosoft.amp.openapi.governance.ApprovalInbox;
import com.sinosoft.amp.openapi.governance.ApprovalItem;
import com.sinosoft.amp.openapi.governance.GovernanceService;
import com.sinosoft.amp.openapi.ops.OtelBridge;
import com.sinosoft.amp.openapi.ops.PlatformMetrics;
import com.sinosoft.amp.openapi.ops.RuntimeConfigService;
import com.sinosoft.amp.openapi.ops.SessionLogService;
import com.sinosoft.amp.openapi.ops.OpsProperties;
import com.sinosoft.amp.openapi.security.AuthContext;
import com.sinosoft.amp.openapi.security.AuthProperties;
import com.sinosoft.amp.openapi.session.AgentEventJson;
import com.sinosoft.amp.openapi.session.SessionEventHub;
import com.sinosoft.amp.openapi.session.SessionRunTracker;
import com.sinosoft.amp.openapi.session.SseReplayBuffer;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.ConfirmResult;
import io.agentscope.core.event.RequireUserConfirmEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.tool.Toolkit;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.agentscope.harness.agent.HarnessAgent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import reactor.core.publisher.Flux;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HITL confirm 端到端契约测试（不启 Spring 上下文）：
 * 暂停 → 409 + 审批落库 → confirm 恢复 → 回复非空且回填 content → 建议规则随确认回传。
 */
class ChatConfirmContractTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private CatalogRuntimeService catalogRuntime;
    private GovernanceService governance;
    private HarnessAgent agent;
    private AgentInstance instance;
    private AgentInstanceCache cache;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        catalogRuntime = mock(CatalogRuntimeService.class);
        governance = mock(GovernanceService.class);
        when(governance.pending(anyString(), anyString(), anyString())).thenReturn(List.of());
        when(governance.findApprovalByIdempotency(anyString())).thenReturn(java.util.Optional.empty());

        agent = mock(HarnessAgent.class);
        when(agent.getToolkit()).thenReturn(new Toolkit());
        cache = new AgentInstanceCache(
                (tenant, agentId, version, modelId, sysPrompt, workspace) -> agent, 10, 1000);
        instance = cache.getOrCreate("acme", "demo", 1, "deepseek:deepseek-flash", "sys", "ws");
        when(catalogRuntime.resolve(anyString(), eq("demo"))).thenReturn(instance);

        PlatformMetrics metrics = new PlatformMetrics(
                new SimpleMeterRegistry(),
                new RuntimeConfigService(new OpsProperties(true, 120, 4, 100)));
        OtelBridge otel = mock(OtelBridge.class);
        when(otel.inSpan(anyString(), anyMap(), any())).thenAnswer(inv ->
                ((Supplier<?>) inv.getArgument(2)).get());

        SseReplayBuffer buffer = new SseReplayBuffer(30_000);
        ChatController controller = new ChatController(
                catalogRuntime,
                cache,
                governance,
                metrics,
                buffer,
                null,
                null,
                otel,
                mock(SessionLogService.class),
                mapper,
                new SessionEventHub(buffer),
                new SessionRunTracker(),
                new AgentEventJson(mapper),
                new ApprovalInbox(governance, mapper),
                mock(com.sinosoft.amp.openapi.session.SessionStateRuleScrubber.class));
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
        AuthContext.set(new AuthProperties.KeyEntry("open-key", "acme", List.of(), List.of("open")));
    }

    @AfterEach
    void tearDown() {
        AuthContext.clear();
    }

    private static ToolUseBlock pendingBlock() {
        return ToolUseBlock.builder()
                .id("call_00_pending")
                .name("dangerous_write")
                .input(Map.of("product", "书", "quantity", 1))
                .content("{\"product\":\"书\",\"quantity\":1}")
                .build();
    }

    private static AgentResultEvent finalResult(String text) {
        return new AgentResultEvent(UserMessage.builder()
                .name("demo")
                .textContent(text)
                .build());
    }

    @Test
    void messagePauseReturns409AndOpensApproval() throws Exception {
        when(agent.streamEvents(anyList(), any(RuntimeContext.class))).thenReturn(Flux.just(
                new RequireUserConfirmEvent("reply-1", List.of(pendingBlock()))));

        mockMvc.perform(post("/api/v1/agents/demo/messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"u1\",\"sessionId\":\"confirm-e2e-1\",\"message\":\"下单\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("HITL_CONFIRM_REQUIRED"));

        verify(governance).openApproval(any(ApprovalItem.class));
    }

    @Test
    void confirmResumesWithContentFilledAndReturnsFinalReply() throws Exception {
        when(agent.streamEvents(anyList(), any(RuntimeContext.class)))
                .thenReturn(Flux.just(
                        new RequireUserConfirmEvent("reply-1", List.of(pendingBlock()))))
                .thenReturn(Flux.just(finalResult("记录已创建 REC-1")));

        ApprovalItem pending = new ApprovalItem(
                "apr-1", "acme", "demo", "u1", "confirm-e2e-2",
                "reply-1", "call_00_pending", "dangerous_write",
                "{\"product\":\"书\",\"quantity\":1}",
                ApprovalItem.PENDING, null, null, Instant.now(), null);
        when(governance.pending("acme", "demo", "confirm-e2e-2")).thenReturn(List.of(pending));

        // 第一步：触发暂停
        mockMvc.perform(post("/api/v1/agents/demo/messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"u1\",\"sessionId\":\"confirm-e2e-2\",\"message\":\"下单\"}"))
                .andExpect(status().isConflict());

        // 第二步：批准恢复
        mockMvc.perform(post("/api/v1/agents/demo/sessions/confirm-e2e-2/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"u1\",\"toolCalls\":[{\"toolCallId\":\"call_00_pending\","
                                + "\"toolName\":\"dangerous_write\",\"confirmed\":true,"
                                + "\"input\":{\"product\":\"书\",\"quantity\":1}}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reply").value("记录已创建 REC-1"));

        // 回归护栏：恢复消息里的 ToolUseBlock 必须带 content（否则 schema 校验失败 → 空回复老 bug）
        ArgumentCaptor<List<Msg>> msgs = ArgumentCaptor.forClass(List.class);
        verify(agent, org.mockito.Mockito.times(2)).streamEvents(msgs.capture(), any(RuntimeContext.class));
        List<Msg> resumeMsgs = msgs.getAllValues().get(msgs.getAllValues().size() - 1);
        Msg resume = resumeMsgs.get(0);
        @SuppressWarnings("unchecked")
        List<ConfirmResult> results =
                (List<ConfirmResult>) resume.getMetadata().get(Msg.METADATA_CONFIRM_RESULTS);
        ConfirmResult first = results.get(0);
        org.junit.jupiter.api.Assertions.assertTrue(first.isConfirmed());
        org.junit.jupiter.api.Assertions.assertEquals(
                "{\"product\":\"书\",\"quantity\":1}", first.getToolCall().getContent());
        org.junit.jupiter.api.Assertions.assertNull(first.getRules());

        verify(governance).decide(eq("apr-1"), eq(true), any(), any());
    }

    @Test
    void confirmWithAcceptRuleCarriesSessionRules() throws Exception {
        when(agent.streamEvents(anyList(), any(RuntimeContext.class)))
                .thenReturn(Flux.just(
                        new RequireUserConfirmEvent("reply-1", List.of(pendingBlock()))))
                .thenReturn(Flux.just(finalResult("ok")));

        mockMvc.perform(post("/api/v1/agents/demo/messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"u1\",\"sessionId\":\"confirm-e2e-3\",\"message\":\"下单\"}"))
                .andExpect(status().isConflict());

        mockMvc.perform(post("/api/v1/agents/demo/sessions/confirm-e2e-3/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"u1\",\"acceptRule\":true,\"toolCalls\":["
                                + "{\"toolCallId\":\"call_00_pending\",\"toolName\":\"dangerous_write\","
                                + "\"confirmed\":true,\"input\":{\"product\":\"书\",\"quantity\":1}}]}"))
                .andExpect(status().isOk());

        ArgumentCaptor<List<Msg>> msgs = ArgumentCaptor.forClass(List.class);
        verify(agent, org.mockito.Mockito.times(2)).streamEvents(msgs.capture(), any(RuntimeContext.class));
        List<List<Msg>> all = msgs.getAllValues();
        Msg resume = all.get(all.size() - 1).get(0);
        @SuppressWarnings("unchecked")
        List<ConfirmResult> results =
                (List<ConfirmResult>) resume.getMetadata().get(Msg.METADATA_CONFIRM_RESULTS);
        List<io.agentscope.core.permission.PermissionRule> rules = results.get(0).getRules();
        org.junit.jupiter.api.Assertions.assertNotNull(rules);
        org.junit.jupiter.api.Assertions.assertFalse(rules.isEmpty());
        org.junit.jupiter.api.Assertions.assertEquals(
                io.agentscope.core.permission.PermissionBehavior.ALLOW, rules.get(0).behavior());
        org.junit.jupiter.api.Assertions.assertEquals("session", rules.get(0).source());
    }

    @Test
    void approvalsExposeSuggestedRules() throws Exception {
        ApprovalItem pending = new ApprovalItem(
                "apr-2", "acme", "demo", "u1", "confirm-e2e-4",
                "reply-1", "call_00_pending", "dangerous_write",
                "{\"product\":\"书\"}",
                ApprovalItem.PENDING, null, null, Instant.now(), null);
        when(governance.pending("acme", "demo", "confirm-e2e-4")).thenReturn(List.of(pending));

        mockMvc.perform(get("/api/v1/agents/demo/sessions/confirm-e2e-4/approvals")
                        .param("userId", "u1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pending[0].toolName").value("dangerous_write"))
                .andExpect(jsonPath("$.pending[0].suggestedRules[0].behavior").value("ALLOW"))
                .andExpect(jsonPath("$.pending[0].suggestedRules[0].source").value("session"));
    }
}
