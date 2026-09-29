package com.sinosoft.amp.openapi.governance;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.RequireUserConfirmEvent;
import io.agentscope.core.message.ToolUseBlock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * RequireUserConfirmEvent → approval_item 落库（含同 toolCallId 去重）。
 * 流式触发、非流式事件收集与唤醒恢复三条路径共用，保证任一路径暂停都有可查询的审批项。
 */
@Component
public class ApprovalInbox {

    private static final Logger log = LoggerFactory.getLogger(ApprovalInbox.class);

    private final GovernanceService governance;
    private final ObjectMapper objectMapper;

    public ApprovalInbox(GovernanceService governance, ObjectMapper objectMapper) {
        this.governance = governance;
        this.objectMapper = objectMapper;
    }

    public void capture(String tenant, String agentId, String userId, String sessionId, AgentEvent event) {
        if (!(event instanceof RequireUserConfirmEvent confirm)) {
            return;
        }
        List<ToolUseBlock> toolCalls = confirm.getToolCalls();
        if (toolCalls == null || toolCalls.isEmpty()) {
            return;
        }
        Set<String> alreadyPending = governance.pending(tenant, agentId, sessionId).stream()
                .map(ApprovalItem::toolCallId)
                .collect(Collectors.toSet());
        for (ToolUseBlock block : toolCalls) {
            if (alreadyPending.contains(block.getId())) {
                continue;
            }
            ApprovalItem item = new ApprovalItem(
                    GovernanceService.newId(),
                    tenant,
                    agentId,
                    userId,
                    sessionId,
                    confirm.getReplyId(),
                    block.getId(),
                    block.getName(),
                    toJson(block.getInput()),
                    ApprovalItem.PENDING,
                    null,
                    null,
                    Instant.now(),
                    null);
            governance.openApproval(item);
            log.info("approval opened id={} tool={} session={}", item.id(), item.toolName(), sessionId);
        }
    }

    private String toJson(Object input) {
        try {
            return objectMapper.writeValueAsString(input == null ? java.util.Map.of() : input);
        } catch (Exception e) {
            return "{}";
        }
    }
}
