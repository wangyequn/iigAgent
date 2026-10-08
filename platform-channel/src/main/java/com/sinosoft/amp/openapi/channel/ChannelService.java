package com.sinosoft.amp.openapi.channel;

import com.sinosoft.amp.openapi.orchestration.WorkflowAgentInvoker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Channel 入站处理：校验 → 映射会话 → 调 Agent（S9 用 Invoker 占位）→ 回投。
 */
@Service
public class ChannelService {

    private static final Logger log = LoggerFactory.getLogger(ChannelService.class);

    private final List<ChannelAdapter> adapters;
    private final WorkflowAgentInvoker agentInvoker;
    private final ChannelBindingService bindings;

    public ChannelService(
            List<ChannelAdapter> adapters,
            @org.springframework.beans.factory.annotation.Qualifier("runtimeWorkflowAgentInvoker")
            WorkflowAgentInvoker agentInvoker,
            ChannelBindingService bindings) {
        this.adapters = adapters;
        this.agentInvoker = agentInvoker;
        this.bindings = bindings;
    }

    public ChannelAdapter requireAdapter(String channelType) {
        String t = MockChannelAdapter.normalizeType(channelType);
        return adapters.stream()
                .filter(a -> a.channelType().equalsIgnoreCase(t))
                .findFirst()
                .orElseThrow(() -> new ChannelException("CHANNEL_NOT_FOUND", "channel type not registered: " + channelType));
    }

    public Map<String, Object> handleInbound(String channelType, String signature, String rawBody) {
        ChannelAdapter adapter = requireAdapter(channelType);
        adapter.verifySignature(signature, null, rawBody);
        ChannelInboundMessage msg = adapter.parseInbound(rawBody);
        String userId = msg.externalUserId() == null ? "anonymous" : msg.externalUserId();
        String sessionId = msg.externalThreadId() == null
                ? ("ch-" + userId)
                : ("ch-" + msg.externalThreadId());
        String tenant;
        try {
            tenant = com.sinosoft.amp.openapi.security.AuthContext.requireTenantId();
        } catch (Exception e) {
            tenant = "acme";
        }
        String agentId = bindings == null
                ? "demo"
                : bindings.resolveAgent(tenant, adapter.channelType()).orElse("demo");
        WorkflowAgentInvoker.AgentResult result = agentInvoker.invoke(
                tenant, agentId, userId, sessionId, msg.text());
        ChannelOutboundMessage out = new ChannelOutboundMessage(result.text(), false);
        adapter.deliver(userId, out);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("channel", adapter.channelType());
        resp.put("agentId", agentId);
        resp.put("userId", userId);
        resp.put("sessionId", sessionId);
        resp.put("reply", out.text());
        log.info("channel inbound handled channel={} user={}", adapter.channelType(), userId);
        return resp;
    }
}
