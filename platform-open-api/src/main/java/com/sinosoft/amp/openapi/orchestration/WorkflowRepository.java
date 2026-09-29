package com.sinosoft.amp.openapi.orchestration;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface WorkflowRepository {

    record WorkflowRecord(
            String tenantId,
            String workflowId,
            String name,
            String description,
            String definitionJson,
            String status,
            Instant createdAt,
            Instant updatedAt) {
    }

    record RevisionRecord(
            String tenantId,
            String workflowId,
            int revision,
            String definitionJson,
            String status,
            Instant publishedAt) {
    }

    record RunRecord(
            String id,
            String tenantId,
            String workflowId,
            int revision,
            String status,
            String currentStep,
            String inputPayload,
            String outputPayload,
            String errorMessage,
            String createdBy,
            Instant createdAt,
            Instant updatedAt) {
    }

    record StepRunRecord(
            String id,
            String runId,
            String stepId,
            String stepType,
            String status,
            String inputPayload,
            String outputPayload,
            Instant startedAt,
            Instant finishedAt) {
    }

    void saveWorkflow(WorkflowRecord record);

    Optional<WorkflowRecord> findWorkflow(String tenantId, String workflowId);

    List<WorkflowRecord> listWorkflows(String tenantId);

    void saveRevision(RevisionRecord record);

    Optional<RevisionRecord> findRevision(String tenantId, String workflowId, int revision);

    Optional<RevisionRecord> findLatestPublishedRevision(String tenantId, String workflowId);

    int nextRevisionNumber(String tenantId, String workflowId);

    void saveRun(RunRecord record);

    Optional<RunRecord> findRun(String runId);

    List<RunRecord> listRuns(String tenantId, int limit);

    void saveStepRun(StepRunRecord record);

    List<StepRunRecord> listStepRuns(String runId);
}
