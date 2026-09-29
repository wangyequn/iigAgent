package com.sinosoft.amp.openapi.orchestration;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 固定步骤本地执行器（P2-B1 降级路径，S9 竖切）。
 * 步骤：ECHO / AGENT / HUMAN_GATE；HUMAN_GATE 挂起等待 confirm。
 */
@Component
public class LocalWorkflowRunner {

    private static final Logger log = LoggerFactory.getLogger(LocalWorkflowRunner.class);

    private final WorkflowRepository repo;
    private final WorkflowAgentInvoker agentInvoker;
    private final ObjectMapper objectMapper;
    private final RunEventBus eventBus;

    /** runId -> 挂起中的 step 信息 */
    private final Map<String, PendingGate> pendingGates = new ConcurrentHashMap<>();

    record PendingGate(String runId, String stepId, WorkflowDefinition definition, Map<String, Object> context) {
    }

    public LocalWorkflowRunner(
            WorkflowRepository repo,
            @org.springframework.beans.factory.annotation.Qualifier("runtimeWorkflowAgentInvoker")
            WorkflowAgentInvoker agentInvoker,
            ObjectMapper objectMapper,
            RunEventBus eventBus) {
        this.repo = repo;
        this.agentInvoker = agentInvoker;
        this.objectMapper = objectMapper;
        this.eventBus = eventBus == null ? new RunEventBus() : eventBus;
    }

    public Map<String, Object> start(String tenantId, String runId, String definitionJson, Map<String, Object> input) {
        WorkflowDefinition def = parseDefinition(definitionJson);
        Map<String, Object> ctx = new LinkedHashMap<>();
        if (input != null) {
            ctx.putAll(input);
        }
        WorkflowRepository.RunRecord run = requireRun(runId);
        updateRun(run, WorkflowService.RUN_RUNNING, def.firstStep().id(), ctx, null, null);
        eventBus.publish(runId, "RUN_STARTED", def.firstStep().id(), "run started", Map.of("workflowId", run.workflowId()));
        return advance(tenantId, runId, def, ctx, def.firstStep().id());
    }

    private void emit(String runId, String type, String stepId, String message) {
        if (eventBus != null) {
            eventBus.publish(runId, type, stepId, message, Map.of());
        }
    }

    public Map<String, Object> confirm(String tenantId, String runId, boolean approved, String note) {
        PendingGate gate = pendingGates.remove(runId);
        WorkflowRepository.RunRecord run = requireRun(runId);
        if (!run.tenantId().equals(tenantId)) {
            throw new OrchestrationException("RUN_NOT_FOUND", "run not found: " + runId);
        }
        if (gate == null) {
            if (WorkflowService.RUN_WAITING_CONFIRM.equals(run.status())) {
                // 重启进程后从 current_step 恢复门闩
                WorkflowDefinition def = parseDefinition(run.outputPayload() == null
                        ? findDefinitionJson(run)
                        : findDefinitionJson(run));
                WorkflowDefinition.WorkflowStep step = def.requireStep(run.currentStep());
                Map<String, Object> ctx = new LinkedHashMap<>();
                if (run.inputPayload() != null) {
                    ctx.putAll(jsonMap(run.inputPayload()));
                }
                gate = new PendingGate(runId, step.id(), def, ctx);
            } else {
                throw new OrchestrationException("RUN_NOT_WAITING_CONFIRM",
                        "run is not waiting for confirm: " + runId + " status=" + run.status());
            }
        }
        Map<String, Object> ctx = gate.context();
        WorkflowDefinition.WorkflowStep step = gate.definition().requireStep(gate.stepId());
        Map<String, Object> stepOut = new LinkedHashMap<>();
        stepOut.put("approved", approved);
        if (note != null) {
            stepOut.put("note", note);
        }
        finishStep(runId, step, "SUCCEEDED", ctx, stepOut);
        if (!approved) {
            updateRun(requireRun(runId), WorkflowService.RUN_CANCELLED, step.id(), ctx, Map.of("cancelledAt", Instant.now().toString()), "human gate rejected");
            return runView(runId);
        }
        if (step.isTerminal()) {
            updateRun(requireRun(runId), WorkflowService.RUN_SUCCEEDED, step.id(), ctx, ctx, null);
            return runView(runId);
        }
        return advance(tenantId, runId, gate.definition(), ctx, step.next());
    }

    public Map<String, Object> cancel(String tenantId, String runId) {
        pendingGates.remove(runId);
        WorkflowRepository.RunRecord run = requireRun(runId);
        if (!run.tenantId().equals(tenantId)) {
            throw new OrchestrationException("RUN_NOT_FOUND", "run not found: " + runId);
        }
        if (WorkflowService.RUN_SUCCEEDED.equals(run.status()) || WorkflowService.RUN_FAILED.equals(run.status())) {
            return runView(runId);
        }
        updateRun(run, WorkflowService.RUN_CANCELLED, run.currentStep(), jsonMap(run.inputPayload()),
                jsonMap(run.outputPayload()), "cancelled");
        return runView(runId);
    }

    private Map<String, Object> advance(String tenantId, String runId, WorkflowDefinition def,
                                        Map<String, Object> ctx, String stepId) {
        WorkflowRepository.RunRecord run = requireRun(runId);
        String cursor = stepId;
        while (cursor != null && !cursor.isBlank()) {
            WorkflowDefinition.WorkflowStep step = def.requireStep(cursor);
            updateRun(requireRun(runId), WorkflowService.RUN_RUNNING, cursor, ctx, ctx, null);
            Map<String, Object> stepOut;
            try {
                stepOut = executeStep(tenantId, runId, step, ctx);
            } catch (Exception e) {
                log.warn("workflow step failed runId={} step={} err={}", runId, cursor, e.toString());
                finishStep(runId, step, "FAILED", ctx, Map.of("error", String.valueOf(e.getMessage())));
                updateRun(requireRun(runId), WorkflowService.RUN_FAILED, cursor, ctx, ctx, e.getMessage());
                return runView(runId);
            }
            if (WorkflowService.RUN_WAITING_CONFIRM.equals(requireRun(runId).status())) {
                return runView(runId);
            }
            finishStep(runId, step, "SUCCEEDED", ctx, stepOut);
            if (step.isTerminal()) {
                updateRun(requireRun(runId), WorkflowService.RUN_SUCCEEDED, cursor, ctx, ctx, null);
                return runView(runId);
            }
            cursor = step.next();
        }
        updateRun(requireRun(runId), WorkflowService.RUN_SUCCEEDED, cursor, ctx, ctx, null);
        return runView(runId);
    }

    private Map<String, Object> executeStep(String tenantId, String runId,
                                            WorkflowDefinition.WorkflowStep step, Map<String, Object> ctx) {
        startStep(runId, step, ctx);
        String type = step.normalizedType();
        if (WorkflowDefinition.WorkflowStep.TYPE_ECHO.equals(type)) {
            String out = step.input() == null ? "" : step.input();
            ctx.put(step.id() + ".output", out);
            return Map.of("echo", out);
        }
        if (WorkflowDefinition.WorkflowStep.TYPE_AGENT.equals(type)) {
            String agentId = step.agentId() == null ? "demo" : step.agentId();
            String input = step.input() == null ? String.valueOf(ctx.getOrDefault("goal", "")) : step.input();
            WorkflowAgentInvoker.AgentResult result = agentInvoker.invoke(
                    requireRun(runId).tenantId(), agentId, null, runId, input);
            ctx.put(step.id() + ".output", result.text());
            return Map.of(
                    "agentId", agentId,
                    "text", result.text(),
                    "promptTokens", result.promptTokens(),
                    "completionTokens", result.completionTokens());
        }
        if (WorkflowDefinition.WorkflowStep.TYPE_HUMAN_GATE.equals(type)) {
            pendingGates.put(runId, new PendingGate(runId, step.id(), parseDefinition(findDefinitionJson(requireRun(runId))), ctx));
            updateRun(requireRun(runId), WorkflowService.RUN_WAITING_CONFIRM, step.id(), ctx, ctx, null);
            return Map.of("waiting", true, "prompt", step.prompt() == null ? "confirm" : step.prompt());
        }
        throw new OrchestrationException("WORKFLOW_STEP_TYPE_INVALID", "unsupported type: " + type);
    }

    private void startStep(String runId, WorkflowDefinition.WorkflowStep step, Map<String, Object> ctx) {
        emit(runId, "STEP_STARTED", step.id(), "step started");
        repo.saveStepRun(new WorkflowRepository.StepRunRecord(
                UUID.randomUUID().toString().replace("-", ""),
                runId, step.id(), step.normalizedType(), "RUNNING",
                json(ctx), null, Instant.now(), null));
    }

    private void finishStep(String runId, WorkflowDefinition.WorkflowStep step, String status,
                            Map<String, Object> ctx, Map<String, Object> out) {
        emit(runId, "STEP_FINISHED", step.id(), "step " + status.toLowerCase(java.util.Locale.ROOT));
        // 简化：插入完成记录（同一 step 可能有 RUNNING+SUCCEEDED 两条，查询按时间序）
        repo.saveStepRun(new WorkflowRepository.StepRunRecord(
                UUID.randomUUID().toString().replace("-", ""),
                runId, step.id(), step.normalizedType(), status,
                json(ctx), json(out), Instant.now(), Instant.now()));
    }

    private void updateRun(WorkflowRepository.RunRecord run, String status, String currentStep,
                           Map<String, Object> input, Map<String, Object> output, String error) {
        repo.saveRun(new WorkflowRepository.RunRecord(
                run.id(), run.tenantId(), run.workflowId(), run.revision(),
                status, currentStep,
                input == null ? run.inputPayload() : json(input),
                output == null ? run.outputPayload() : json(output),
                error, run.createdBy(), run.createdAt(), Instant.now()));
    }

    private WorkflowRepository.RunRecord requireRun(String runId) {
        return repo.findRun(runId)
                .orElseThrow(() -> new OrchestrationException("RUN_NOT_FOUND", "run not found: " + runId));
    }

    private String findDefinitionJson(WorkflowRepository.RunRecord run) {
        return repo.findRevision(run.tenantId(), run.workflowId(), run.revision())
                .map(WorkflowRepository.RevisionRecord::definitionJson)
                .orElseThrow(() -> new OrchestrationException(
                        "WORKFLOW_REVISION_NOT_FOUND", "revision missing for run: " + run.id()));
    }

    private Map<String, Object> runView(String runId) {
        WorkflowRepository.RunRecord run = requireRun(runId);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("runId", run.id());
        m.put("tenantId", run.tenantId());
        m.put("workflowId", run.workflowId());
        m.put("revision", run.revision());
        m.put("status", run.status());
        m.put("currentStep", run.currentStep());
        m.put("input", jsonMap(run.inputPayload()));
        m.put("output", jsonMap(run.outputPayload()));
        m.put("errorMessage", run.errorMessage());
        m.put("createdBy", run.createdBy());
        m.put("createdAt", run.createdAt());
        m.put("updatedAt", run.updatedAt());
        m.put("steps", repo.listStepRuns(run.id()).stream()
                .map(s -> {
                    Map<String, Object> sm = new LinkedHashMap<>();
                    sm.put("id", s.id());
                    sm.put("stepId", s.stepId());
                    sm.put("stepType", s.stepType());
                    sm.put("status", s.status());
                    sm.put("input", jsonMap(s.inputPayload()));
                    sm.put("output", jsonMap(s.outputPayload()));
                    sm.put("startedAt", s.startedAt());
                    sm.put("finishedAt", s.finishedAt());
                    return sm;
                })
                .toList());
        return m;
    }

    private WorkflowDefinition parseDefinition(String definitionJson) {
        try {
            Map<String, Object> raw = objectMapper.readValue(
                    definitionJson, new TypeReference<Map<String, Object>>() {
                    });
            return WorkflowDefinition.parse(raw);
        } catch (OrchestrationException e) {
            throw e;
        } catch (Exception e) {
            throw new OrchestrationException("WORKFLOW_DEFINITION_INVALID", "bad definition json: " + e.getMessage());
        }
    }

    private String json(Map<String, Object> m) {
        try {
            return objectMapper.writeValueAsString(m == null ? Map.of() : m);
        } catch (Exception e) {
            return "{}";
        }
    }

    private Map<String, Object> jsonMap(String s) {
        if (s == null || s.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(s, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception e) {
            return Map.of("raw", s);
        }
    }
}
