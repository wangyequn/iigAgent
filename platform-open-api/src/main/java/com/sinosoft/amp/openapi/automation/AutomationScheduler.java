package com.sinosoft.amp.openapi.automation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sinosoft.amp.openapi.orchestration.WorkflowService;
import com.sinosoft.amp.openapi.security.AuthProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * S14：扫描到期 CRON 触发器并启动目标 Workflow Run（@Scheduled + 库表条件，T3 路线）。
 * 多副本可后续加 DB 锁；当前 best-effort，重复 fire 由 Run 幂等扩展。
 */
@Component
public class AutomationScheduler {

    private static final Logger log = LoggerFactory.getLogger(AutomationScheduler.class);

    private final AutomationService automations;
    private final WorkflowService workflows;
    private final ObjectMapper objectMapper;

    public AutomationScheduler(
            AutomationService automations,
            WorkflowService workflows,
            ObjectMapper objectMapper) {
        this.automations = automations;
        this.workflows = workflows;
        this.objectMapper = objectMapper;
    }

    @Scheduled(fixedDelayString = "${amp.automation.sweep-ms:30000}")
    public void sweep() {
        List<Map<String, Object>> due = automations.dueCronTriggers();
        for (Map<String, Object> row : due) {
            String id = String.valueOf(row.get("id"));
            String tenant = String.valueOf(row.get("tenant_id"));
            String workflowId = row.get("target_workflow_id") == null
                    ? null : String.valueOf(row.get("target_workflow_id"));
            try {
                if (workflowId == null || workflowId.isBlank()) {
                    log.warn("cron trigger {} has no target workflow, skip", id);
                    continue;
                }
                Map<String, Object> payload = parsePayload(row.get("payload"));
                fire(tenant, workflowId, payload);
                log.info("cron automation fired id={} workflow={}", id, workflowId);
            } catch (Exception e) {
                log.warn("cron automation failed id={} err={}", id, e.toString());
            }
        }
    }

    void fire(String tenantId, String workflowId, Map<String, Object> payload) {
        AuthProperties.KeyEntry sys = new AuthProperties.KeyEntry(
                "automation", tenantId, List.of("demo"), List.of("open", "admin"));
        com.sinosoft.amp.openapi.security.AuthContext.set(sys);
        try {
            workflows.startRun(workflowId, null, payload, "automation");
        } finally {
            com.sinosoft.amp.openapi.security.AuthContext.clear();
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parsePayload(Object raw) {
        if (raw == null) {
            return Map.of();
        }
        if (raw instanceof Map<?, ?> m) {
            return (Map<String, Object>) m;
        }
        String s = String.valueOf(raw);
        if (s.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(s, Map.class);
        } catch (Exception e) {
            return Map.of("raw", s);
        }
    }
}
