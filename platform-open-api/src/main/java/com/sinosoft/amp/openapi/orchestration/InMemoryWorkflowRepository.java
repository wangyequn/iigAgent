package com.sinosoft.amp.openapi.orchestration;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** 测试与无库场景用内存仓储。 */
public class InMemoryWorkflowRepository implements WorkflowRepository {

    private final Map<String, WorkflowRecord> workflows = new ConcurrentHashMap<>();
    private final Map<String, RevisionRecord> revisions = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> revSeq = new ConcurrentHashMap<>();
    private final Map<String, RunRecord> runs = new ConcurrentHashMap<>();
    private final Map<String, List<StepRunRecord>> stepRuns = new ConcurrentHashMap<>();

    private static String wKey(String tenantId, String workflowId) {
        return tenantId + ":" + workflowId;
    }

    private static String rKey(String tenantId, String workflowId, int revision) {
        return tenantId + ":" + workflowId + ":" + revision;
    }

    @Override
    public void saveWorkflow(WorkflowRecord record) {
        workflows.put(wKey(record.tenantId(), record.workflowId()), record);
    }

    @Override
    public Optional<WorkflowRecord> findWorkflow(String tenantId, String workflowId) {
        return Optional.ofNullable(workflows.get(wKey(tenantId, workflowId)));
    }

    @Override
    public List<WorkflowRecord> listWorkflows(String tenantId) {
        List<WorkflowRecord> out = new ArrayList<>();
        for (WorkflowRecord r : workflows.values()) {
            if (r.tenantId().equals(tenantId)) {
                out.add(r);
            }
        }
        out.sort(Comparator.comparing(WorkflowRecord::workflowId));
        return out;
    }

    @Override
    public void saveRevision(RevisionRecord record) {
        revisions.put(rKey(record.tenantId(), record.workflowId(), record.revision()), record);
    }

    @Override
    public Optional<RevisionRecord> findRevision(String tenantId, String workflowId, int revision) {
        return Optional.ofNullable(revisions.get(rKey(tenantId, workflowId, revision)));
    }

    @Override
    public Optional<RevisionRecord> findLatestPublishedRevision(String tenantId, String workflowId) {
        return revisions.values().stream()
                .filter(r -> r.tenantId().equals(tenantId) && r.workflowId().equals(workflowId))
                .filter(r -> "PUBLISHED".equalsIgnoreCase(r.status()))
                .max(Comparator.comparingInt(RevisionRecord::revision));
    }

    @Override
    public int nextRevisionNumber(String tenantId, String workflowId) {
        return revSeq.computeIfAbsent(wKey(tenantId, workflowId), k -> new AtomicInteger(0))
                .incrementAndGet();
    }

    @Override
    public void saveRun(RunRecord record) {
        runs.put(record.id(), record);
    }

    @Override
    public Optional<RunRecord> findRun(String runId) {
        return Optional.ofNullable(runs.get(runId));
    }

    @Override
    public List<RunRecord> listRuns(String tenantId, int limit) {
        List<RunRecord> out = new ArrayList<>();
        for (RunRecord r : runs.values()) {
            if (r.tenantId().equals(tenantId)) {
                out.add(r);
            }
        }
        out.sort(Comparator.comparing(RunRecord::createdAt).reversed());
        return out.size() > limit ? out.subList(0, limit) : out;
    }

    @Override
    public void saveStepRun(StepRunRecord record) {
        stepRuns.computeIfAbsent(record.runId(), k -> new java.util.concurrent.CopyOnWriteArrayList<>())
                .add(record);
    }

    @Override
    public List<StepRunRecord> listStepRuns(String runId) {
        return List.copyOf(stepRuns.getOrDefault(runId, List.of()));
    }

    static Instant now() {
        return Instant.now();
    }
}
