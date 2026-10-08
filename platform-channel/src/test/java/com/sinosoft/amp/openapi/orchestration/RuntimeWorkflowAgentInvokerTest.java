package com.sinosoft.amp.openapi.orchestration;

import com.sinosoft.amp.openapi.agent.AgentInstance;
import com.sinosoft.amp.openapi.agent.AgentInstanceCache;
import com.sinosoft.amp.openapi.agent.CatalogRuntimeService;
import com.sinosoft.amp.openapi.billing.QuotaService;
import com.sinosoft.amp.openapi.catalog.CatalogException;
import com.sinosoft.amp.openapi.external.ExternalChatClient;
import com.sinosoft.amp.openapi.ops.PlatformMetrics;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.harness.agent.HarnessAgent;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RuntimeWorkflowAgentInvokerTest {

    @Test
    void managedAgentReturnsText() {
        CatalogRuntimeService catalog = mock(CatalogRuntimeService.class);
        AgentInstanceCache cache = mock(AgentInstanceCache.class);
        ExternalChatClient external = mock(ExternalChatClient.class);
        QuotaService quotas = mock(QuotaService.class);
        PlatformMetrics metrics = mock(PlatformMetrics.class);
        AgentInstance instance = mock(AgentInstance.class);
        AgentInstanceCache.Lease lease = mockAgentLease("处理完成: 查状态");
        when(catalog.resolve("acme", "demo")).thenReturn(instance);
        when(cache.lease(instance)).thenReturn(lease);
        when(metrics.estimateTokens(anyString())).thenReturn(3);

        RuntimeWorkflowAgentInvoker invoker = new RuntimeWorkflowAgentInvoker(
                catalog, cache, external, quotas, metrics);
        WorkflowAgentInvoker.AgentResult r = invoker.invoke("acme", "demo", "u1", "s1", "查状态");
        assertTrue(r.text().contains("处理完成"));
        assertEquals(3, r.promptTokens());
        verify(quotas).check("acme");
        verify(quotas).record(eq("acme"), eq("demo"), eq("s1"), eq("u1"), eq(3), eq(3),
                anyLong(), eq("workflow"));
        verify(external, never()).forwardMessage(any(), any(), any(), any(), any());
    }

    @Test
    void externalFallbackWhenNotManaged() {
        CatalogRuntimeService catalog = mock(CatalogRuntimeService.class);
        AgentInstanceCache cache = mock(AgentInstanceCache.class);
        ExternalChatClient external = mock(ExternalChatClient.class);
        QuotaService quotas = mock(QuotaService.class);
        PlatformMetrics metrics = mock(PlatformMetrics.class);
        when(catalog.resolve("acme", "ext1")).thenThrow(new CatalogException("AGENT_NOT_FOUND", "x"));
        when(external.hasExternal("acme", "ext1")).thenReturn(true);
        when(external.forwardMessage("acme", "ext1", "u1", "s1", "hi"))
                .thenReturn(Map.of("reply", "from-external"));
        when(metrics.estimateTokens(anyString())).thenReturn(1);

        RuntimeWorkflowAgentInvoker invoker = new RuntimeWorkflowAgentInvoker(
                catalog, cache, external, quotas, metrics);
        WorkflowAgentInvoker.AgentResult r = invoker.invoke("acme", "ext1", "u1", "s1", "hi");
        assertEquals("from-external", r.text());
    }

    @Test
    void blankSessionUsesDefault() {
        CatalogRuntimeService catalog = mock(CatalogRuntimeService.class);
        AgentInstanceCache cache = mock(AgentInstanceCache.class);
        AgentInstance instance = mock(AgentInstance.class);
        AgentInstanceCache.Lease lease = mockAgentLease("ok");
        when(catalog.resolve("acme", "demo")).thenReturn(instance);
        when(cache.lease(instance)).thenReturn(lease);
        PlatformMetrics metrics = mock(PlatformMetrics.class);
        when(metrics.estimateTokens(anyString())).thenReturn(1);
        RuntimeWorkflowAgentInvoker invoker = new RuntimeWorkflowAgentInvoker(
                catalog, cache, mock(ExternalChatClient.class), mock(QuotaService.class), metrics);
        WorkflowAgentInvoker.AgentResult r = invoker.invoke("acme", "demo", null, null, "x");
        assertEquals("ok", r.text());
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static AgentInstanceCache.Lease mockAgentLease(String reply) {
        AgentInstanceCache.Lease lease = mock(AgentInstanceCache.Lease.class);
        HarnessAgent agent = mock(HarnessAgent.class);
        Msg msg = mock(Msg.class);
        when(msg.getTextContent()).thenReturn(reply);
        when(agent.call(any(List.class), any(RuntimeContext.class))).thenReturn(Mono.just(msg));
        when(lease.agent()).thenReturn(agent);
        return lease;
    }
}
