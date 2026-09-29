package com.sinosoft.amp.openapi.orchestration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sinosoft.amp.openapi.security.AuthContext;
import com.sinosoft.amp.openapi.security.AuthProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalWorkflowRunnerTest {

    private InMemoryWorkflowRepository repo;
    private LocalWorkflowRunner runner;
    private WorkflowService service;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        repo = new InMemoryWorkflowRepository();
        runner = new LocalWorkflowRunner(repo, new EchoWorkflowAgentInvoker(), mapper, new RunEventBus());
        service = new WorkflowService(repo, runner, mapper);
        AuthProperties.KeyEntry entry = new AuthProperties.KeyEntry(
                "test-key", "acme", List.of("demo"), List.of("open", "admin"));
        AuthContext.set(entry);
    }

    @AfterEach
    void tearDown() {
        AuthContext.clear();
    }

    @Test
    void echoWorkflowSucceeds() {
        Map<String, Object> def = definition(
                Map.of("id", "s1", "type", "ECHO", "input", "hello", "next", "s2"),
                Map.of("id", "s2", "type", "ECHO", "input", "world"));
        service.upsertWorkflow("wf-echo", "echo", null, def);
        service.publishRevision("wf-echo");
        Map<String, Object> run = service.startRun("wf-echo", null, Map.of("goal", "g"), "u1");
        assertEquals(WorkflowService.RUN_SUCCEEDED, run.get("status"));
        assertNotNull(run.get("steps"));
        assertTrue(((List<?>) run.get("steps")).size() >= 2);
    }

    @Test
    void humanGateWaitsThenConfirmApprove() {
        Map<String, Object> def = definition(
                Map.of("id", "s1", "type", "ECHO", "input", "prep", "next", "gate"),
                Map.of("id", "gate", "type", "HUMAN_GATE", "prompt", "ok?", "next", "s3"),
                Map.of("id", "s3", "type", "ECHO", "input", "after"));
        service.upsertWorkflow("wf-gate", "gate", null, def);
        service.publishRevision("wf-gate");
        Map<String, Object> run = service.startRun("wf-gate", null, Map.of(), "u1");
        assertEquals(WorkflowService.RUN_WAITING_CONFIRM, run.get("status"));
        assertEquals("gate", run.get("currentStep"));
        Map<String, Object> done = service.confirmRun(String.valueOf(run.get("runId")), true, "ok");
        assertEquals(WorkflowService.RUN_SUCCEEDED, done.get("status"));
    }

    @Test
    void humanGateRejectCancels() {
        Map<String, Object> def = definition(
                Map.of("id", "gate", "type", "HUMAN_GATE", "prompt", "ok?"));
        service.upsertWorkflow("wf-rej", "rej", null, def);
        service.publishRevision("wf-rej");
        Map<String, Object> run = service.startRun("wf-rej", null, Map.of(), "u1");
        assertEquals(WorkflowService.RUN_WAITING_CONFIRM, run.get("status"));
        Map<String, Object> after = service.confirmRun(String.valueOf(run.get("runId")), false, "no");
        assertEquals(WorkflowService.RUN_CANCELLED, after.get("status"));
    }

    @Test
    void agentStepUsesInvoker() {
        Map<String, Object> def = definition(
                Map.of("id", "a1", "type", "AGENT", "agentId", "demo", "input", "排查订单"));
        service.upsertWorkflow("wf-agent", "agent", null, def);
        service.publishRevision("wf-agent");
        Map<String, Object> run = service.startRun("wf-agent", null, Map.of(), "u1");
        assertEquals(WorkflowService.RUN_SUCCEEDED, run.get("status"));
    }

    @Test
    void startWithoutPublishedRevisionFails() {
        Map<String, Object> def = definition(Map.of("id", "s1", "type", "ECHO", "input", "x"));
        service.upsertWorkflow("wf-np", "np", null, def);
        OrchestrationException ex = assertThrows(OrchestrationException.class,
                () -> service.startRun("wf-np", null, Map.of(), "u1"));
        assertEquals("WORKFLOW_REVISION_NOT_PUBLISHED", ex.code());
    }

    @Test
    void invalidStepTypeRejected() {
        Map<String, Object> def = definition(Map.of("id", "s1", "type", "NOPE", "input", "x"));
        OrchestrationException ex = assertThrows(OrchestrationException.class,
                () -> service.upsertWorkflow("wf-bad", "bad", null, def));
        assertEquals("WORKFLOW_STEP_TYPE_INVALID", ex.code());
    }

    private static Map<String, Object> definition(Map<String, Object>... steps) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("steps", List.of(steps));
        return m;
    }
}
