package com.sinosoft.amp.openapi.automation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sinosoft.amp.openapi.automation.AutomationScheduler;
import com.sinosoft.amp.openapi.automation.AutomationService;import com.sinosoft.amp.openapi.orchestration.LocalWorkflowRunner;
import com.sinosoft.amp.openapi.orchestration.InMemoryWorkflowRepository;
import com.sinosoft.amp.openapi.orchestration.WorkflowService;
import com.sinosoft.amp.openapi.orchestration.WorkflowRepository;
import com.sinosoft.amp.openapi.security.AuthContext;
import com.sinosoft.amp.openapi.security.AuthProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 任务调度测试：CRON 到期扫描 → 触发目标 Workflow Run；
 * 无目标跳过、payload 解析容错、系统身份注入与清理。
 */
class AutomationSchedulerTest {

    private AutomationScheduler scheduler;
    private WorkflowService workflows;
    private InMemoryWorkflowRepository repo;
    private FakeAutomationService automations;

    @BeforeEach
    void setUp() {
        repo = new InMemoryWorkflowRepository();
        LocalWorkflowRunner runner = mock(LocalWorkflowRunner.class);
        when(runner.start(anyString(), anyString(), any(), any()))
                .thenAnswer(inv -> Map.of(
                        "runId", inv.getArgument(1),
                        "status", WorkflowService.RUN_RUNNING));
        workflows = new WorkflowService(repo, runner, new ObjectMapper());
        automations = new FakeAutomationService();
        scheduler = new AutomationScheduler(automations, workflows, new ObjectMapper());
    }

    @AfterEach
    void tearDown() {
        AuthContext.clear();
    }

    private void seedPublishedWorkflow(String tenant, String workflowId) {
        repo.saveWorkflow(new WorkflowRepository.WorkflowRecord(
                tenant, workflowId, workflowId, "test",
                "{\"steps\":[]}", "ACTIVE", Instant.now(), Instant.now()));
        repo.saveRevision(new WorkflowRepository.RevisionRecord(
                tenant, workflowId, 1, "{\"steps\":[]}", "PUBLISHED", Instant.now()));
    }

    @Test
    void dueCronTriggerFiresWorkflowRun() {
        seedPublishedWorkflow("acme", "wf-nightly");
        automations.due.add(Map.of(
                "id", "trg-1", "tenant_id", "acme",
                "target_workflow_id", "wf-nightly",
                "payload", "{\"city\":\"nanjing\"}"));

        scheduler.sweep();

        List<WorkflowRepository.RunRecord> runs = repo.listRuns("acme", 10);
        assertEquals(1, runs.size());
        assertEquals("wf-nightly", runs.get(0).workflowId());
        assertEquals("automation", runs.get(0).createdBy());
        assertNotNull(runs.get(0).id());
    }

    @Test
    void missingTargetWorkflowSkipped() {
        automations.due.add(Map.of(
                "id", "trg-2", "tenant_id", "acme",
                "target_workflow_id", "",
                "payload", "{}"));
        scheduler.sweep();
        assertEquals(0, repo.listRuns("acme", 10).size());
    }

    @Test
    void unknownWorkflowDoesNotBreakSweep() {
        automations.due.add(Map.of(
                "id", "trg-3", "tenant_id", "acme",
                "target_workflow_id", "no-such-wf",
                "payload", "{}"));
        automations.due.add(Map.of(
                "id", "trg-4", "tenant_id", "acme",
                "target_workflow_id", "wf-nightly",
                "payload", "{}"));
        seedPublishedWorkflow("acme", "wf-nightly");
        scheduler.sweep();
        // 第一条失败不阻塞第二条
        assertEquals(1, repo.listRuns("acme", 10).size());
    }

    @Test
    void fireSetsAndClearsSystemIdentity() {
        seedPublishedWorkflow("acme", "wf-x");
        AuthContext.clear();
        scheduler.fire("acme", "wf-x", Map.of("k", "v"));
        assertEquals(1, repo.listRuns("acme", 10).size());
        org.junit.jupiter.api.Assertions.assertNull(AuthContext.get(),
                "fire 结束后不应残留系统身份");
    }

    @Test
    void payloadJsonStringParsed() {
        seedPublishedWorkflow("acme", "wf-p");
        automations.due.add(new HashMap<>(Map.of(
                "id", "trg-5", "tenant_id", "acme",
                "target_workflow_id", "wf-p")));
        // payload 为 null → 视为空 Map
        scheduler.sweep();
        assertEquals(1, repo.listRuns("acme", 10).size());
    }

    /** 模拟 AutomationService 的 dueCronTriggers，绕开 JDBC。 */
    static class FakeAutomationService extends AutomationService {
        final List<Map<String, Object>> due = new java.util.ArrayList<>();

        FakeAutomationService() {
            super(null);
        }

        @Override
        public List<Map<String, Object>> dueCronTriggers() {
            return due;
        }
    }
}
