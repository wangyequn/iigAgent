package com.sinosoft.amp.openapi.orchestration;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sinosoft.amp.openapi.security.AuthContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Phase2 编排服务：Workflow 定义 / revision 发布 / Run 启停。
 * 执行引擎为 {@link LocalWorkflowRunner}（P2-B1 降级路径）；AgentScope Service 对接预留。
 */
@Service
public class WorkflowService {

    public static final String RUN_PENDING = "PENDING";
    public static final String RUN_RUNNING = "RUNNING";
    public static final String RUN_WAITING_CONFIRM = "WAITING_CONFIRM";
    public static final String RUN_SUCCEEDED = "SUCCEEDED";
    public static final String RUN_FAILED = "FAILED";
    public static final String RUN_CANCELLED = "CANCELLED";

    private static final Logger log = LoggerFactory.getLogger(WorkflowService.class);

    private final WorkflowRepository repo;
    private final LocalWorkflowRunner runner;
    private final ObjectMapper objectMapper;

    public WorkflowService(WorkflowRepository repo, LocalWorkflowRunner runner, ObjectMapper objectMapper) {
        this.repo = repo;
        this.runner = runner;
        this.objectMapper = objectMapper;
    }

    public Map<String, Object> upsertWorkflow(String workflowId, String name, String description, Map<String, Object> definition) {
        String tenant = AuthContext.requireTenantId();
        WorkflowDefinition def = WorkflowDefinition.parse(definition);
        String json = writeJson(Map.of("steps", def.steps().stream().map(this::stepToMap).toList()));
        Instant now = Instant.now();
        WorkflowRepository.WorkflowRecord rec = new WorkflowRepository.WorkflowRecord(
                tenant, workflowId, name, description, json, "DRAFT", now, now);
        repo.saveWorkflow(rec);
        log.info("workflow upserted tenant={} id={}", tenant, workflowId);
        return workflowView(rec);
    }

    public Map<String, Object> publishRevision(String workflowId) {
        String tenant = AuthContext.requireTenantId();
        WorkflowRepository.WorkflowRecord wf = repo.findWorkflow(tenant, workflowId)
                .orElseThrow(() -> new OrchestrationException("WORKFLOW_NOT_FOUND", "workflow not found: " + workflowId));
        int rev = repo.nextRevisionNumber(tenant, workflowId);
        WorkflowRepository.RevisionRecord rr = new WorkflowRepository.RevisionRecord(
                tenant, workflowId, rev, wf.definitionJson(), "PUBLISHED", Instant.now());
        repo.saveRevision(rr);
        log.info("workflow revision published tenant={} id={} rev={}", tenant, workflowId, rev);
        return Map.of(
                "tenantId", tenant,
                "workflowId", workflowId,
                "revision", rev,
                "status", "PUBLISHED");
    }

    public Map<String, Object> startRun(String workflowId, Integer revision, Map<String, Object> input, String userId) {
        String tenant = AuthContext.requireTenantId();
        WorkflowRepository.RevisionRecord revRec;
        if (revision != null) {
            revRec = repo.findRevision(tenant, workflowId, revision)
                    .orElseThrow(() -> new OrchestrationException(
                            "WORKFLOW_REVISION_NOT_FOUND", "revision not found: " + workflowId + "@" + revision));
        } else {
            revRec = repo.findLatestPublishedRevision(tenant, workflowId)
                    .orElseThrow(() -> new OrchestrationException(
                            "WORKFLOW_REVISION_NOT_PUBLISHED", "no published revision for workflow: " + workflowId));
        }
        if (!"PUBLISHED".equalsIgnoreCase(revRec.status())) {
            throw new OrchestrationException("WORKFLOW_REVISION_NOT_PUBLISHED",
                    "revision is not published: " + workflowId + "@" + revRec.revision());
        }
        String runId = UUID.randomUUID().toString().replace("-", "");
        String inputJson = writeJson(input == null ? Map.of() : input);
        WorkflowRepository.RunRecord run = new WorkflowRepository.RunRecord(
                runId, tenant, workflowId, revRec.revision(),
                RUN_PENDING, null, inputJson, null, null,
                userId == null ? AuthContext.actorLabel() : userId,
                Instant.now(), Instant.now());
        repo.saveRun(run);
        Map<String, Object> view = runner.start(tenant, runId, revRec.definitionJson(), input);
        log.info("workflow run started tenant={} workflow={} runId={}", tenant, workflowId, runId);
        return view;
    }

    public Map<String, Object> confirmRun(String runId, boolean approved, String note) {
        String tenant = AuthContext.requireTenantId();
        return runner.confirm(tenant, runId, approved, note);
    }

    public Map<String, Object> cancelRun(String runId) {
        String tenant = AuthContext.requireTenantId();
        return runner.cancel(tenant, runId);
    }

    public Map<String, Object> getRun(String runId) {
        String tenant = AuthContext.requireTenantId();
        WorkflowRepository.RunRecord run = repo.findRun(runId)
                .orElseThrow(() -> new OrchestrationException("RUN_NOT_FOUND", "run not found: " + runId));
        if (!run.tenantId().equals(tenant)) {
            throw new OrchestrationException("RUN_NOT_FOUND", "run not found: " + runId);
        }
        return runView(run);
    }

    public List<Map<String, Object>> listRuns(int limit) {
        String tenant = AuthContext.requireTenantId();
        return repo.listRuns(tenant, Math.max(1, Math.min(limit, 200))).stream().map(this::runView).toList();
    }

    public List<Map<String, Object>> listWorkflows() {
        String tenant = AuthContext.requireTenantId();
        return repo.listWorkflows(tenant).stream().map(this::workflowView).toList();
    }

    Map<String, Object> runView(WorkflowRepository.RunRecord run) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("runId", run.id());
        m.put("tenantId", run.tenantId());
        m.put("workflowId", run.workflowId());
        m.put("revision", run.revision());
        m.put("status", run.status());
        m.put("currentStep", run.currentStep());
        m.put("input", readJson(run.inputPayload()));
        m.put("output", readJson(run.outputPayload()));
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
                    sm.put("input", readJson(s.inputPayload()));
                    sm.put("output", readJson(s.outputPayload()));
                    sm.put("startedAt", s.startedAt());
                    sm.put("finishedAt", s.finishedAt());
                    return sm;
                })
                .toList());
        return m;
    }

    private Map<String, Object> workflowView(WorkflowRepository.WorkflowRecord rec) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("workflowId", rec.workflowId());
        m.put("name", rec.name());
        m.put("description", rec.description());
        m.put("status", rec.status());
        m.put("definition", readJson(rec.definitionJson()));
        m.put("createdAt", rec.createdAt());
        m.put("updatedAt", rec.updatedAt());
        return m;
    }

    private Map<String, Object> stepToMap(WorkflowDefinition.WorkflowStep s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", s.id());
        m.put("type", s.normalizedType());
        if (s.agentId() != null) {
            m.put("agentId", s.agentId());
        }
        if (s.input() != null) {
            m.put("input", s.input());
        }
        if (s.prompt() != null) {
            m.put("prompt", s.prompt());
        }
        if (s.next() != null) {
            m.put("next", s.next());
        }
        return m;
    }

    String writeJson(Object o) {
        try {
            return objectMapper.writeValueAsString(o);
        } catch (Exception e) {
            throw new OrchestrationException("WORKFLOW_DEFINITION_INVALID", "cannot serialize definition: " + e.getMessage());
        }
    }

    Map<String, Object> readJson(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception e) {
            return Map.of("raw", json);
        }
    }
}
