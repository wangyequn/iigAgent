package com.sinosoft.amp.openapi.orchestration;

import com.sinosoft.amp.openapi.agent.AgentInstance;
import com.sinosoft.amp.openapi.agent.AgentInstanceCache;
import com.sinosoft.amp.openapi.agent.CatalogRuntimeService;
import com.sinosoft.amp.openapi.billing.QuotaService;
import com.sinosoft.amp.openapi.catalog.CatalogException;
import com.sinosoft.amp.openapi.external.ExternalChatClient;
import com.sinosoft.amp.openapi.ops.PlatformMetrics;
import com.sinosoft.amp.openapi.session.SessionContext;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * S10：Workflow AGENT 步骤走真实 Harness / External 对话。
 * Bean 名 runtimeWorkflowAgentInvoker，覆盖 Echo 占位。
 */
@Component("runtimeWorkflowAgentInvoker")
@Primary
public class RuntimeWorkflowAgentInvoker implements WorkflowAgentInvoker {

    private static final Logger log = LoggerFactory.getLogger(RuntimeWorkflowAgentInvoker.class);

    private final CatalogRuntimeService catalogRuntime;
    private final AgentInstanceCache instanceCache;
    private final ExternalChatClient externalChat;
    private final QuotaService quotas;
    private final PlatformMetrics metrics;

    public RuntimeWorkflowAgentInvoker(
            CatalogRuntimeService catalogRuntime,
            AgentInstanceCache instanceCache,
            ExternalChatClient externalChat,
            QuotaService quotas,
            PlatformMetrics metrics) {
        this.catalogRuntime = catalogRuntime;
        this.instanceCache = instanceCache;
        this.externalChat = externalChat;
        this.quotas = quotas;
        this.metrics = metrics;
    }

    @Override
    public AgentResult invoke(String tenantId, String agentId, String userId, String sessionId, String input) {
        String uid = userId == null || userId.isBlank() ? "wf-system" : userId;
        String sid = sessionId == null || sessionId.isBlank() ? "wf-session" : sessionId;
        String message = input == null ? "" : input;
        if (quotas != null) {
            quotas.check(tenantId);
        }
        boolean managed;
        try {
            catalogRuntime.resolve(tenantId, agentId);
            managed = true;
        } catch (CatalogException e) {
            managed = false;
        }
        if (!managed && externalChat != null && externalChat.hasExternal(tenantId, agentId)) {
            Map<String, Object> forwarded = externalChat.forwardMessage(tenantId, agentId, uid, sid, message);
            Object text = forwarded.get("reply");
            String out = text == null ? "" : String.valueOf(text);
            return finish(tenantId, agentId, uid, sid, message, out);
        }
        AgentInstance instance = catalogRuntime.resolve(tenantId, agentId);
        RuntimeContext ctx = SessionContext.require(tenantId, uid, sid);
        try (AgentInstanceCache.Lease lease = instanceCache.lease(instance)) {
            Msg result = lease.agent().call(List.of(new UserMessage(message)), ctx).block();
            String text = result == null ? "" : result.getTextContent();
            log.info("workflow.agent.done agentId={} sessionId={} replyChars={}",
                    agentId, sid, text == null ? 0 : text.length());
            return finish(tenantId, agentId, uid, sid, message, text == null ? "" : text);
        }
    }

    private AgentResult finish(String tenantId, String agentId, String uid, String sid,
                               String prompt, String text) {
        int p = metrics == null ? 0 : metrics.estimateTokens(prompt);
        int c = metrics == null ? 0 : metrics.estimateTokens(text);
        if (metrics != null) {
            metrics.recordChat(agentId, 0, false);
            metrics.recordTokens(agentId, p, c);
        }
        if (quotas != null) {
            long cost = metrics == null ? 0 : metrics.costMicros(p + c);
            quotas.record(tenantId, agentId, sid, uid, p, c, cost, "workflow");
        }
        return new AgentResult(text, p, c);
    }
}
