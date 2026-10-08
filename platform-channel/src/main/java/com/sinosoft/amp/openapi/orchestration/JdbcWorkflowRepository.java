package com.sinosoft.amp.openapi.orchestration;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public class JdbcWorkflowRepository implements WorkflowRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcWorkflowRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static Timestamp ts(Instant i) {
        return i == null ? null : Timestamp.from(i);
    }

    private static Instant inst(Timestamp t) {
        return t == null ? null : t.toInstant();
    }

    private WorkflowRecord mapWorkflow(ResultSet rs, int i) throws SQLException {
        return new WorkflowRecord(
                rs.getString("tenant_id"), rs.getString("workflow_id"), rs.getString("name"),
                rs.getString("description"), rs.getString("definition"), rs.getString("status"),
                inst(rs.getTimestamp("created_at")), inst(rs.getTimestamp("updated_at")));
    }

    private RevisionRecord mapRevision(ResultSet rs, int i) throws SQLException {
        return new RevisionRecord(
                rs.getString("tenant_id"), rs.getString("workflow_id"), rs.getInt("revision"),
                rs.getString("definition"), rs.getString("status"), inst(rs.getTimestamp("published_at")));
    }

    private RunRecord mapRun(ResultSet rs, int i) throws SQLException {
        return new RunRecord(
                rs.getString("id"), rs.getString("tenant_id"), rs.getString("workflow_id"),
                rs.getInt("revision"), rs.getString("status"), rs.getString("current_step"),
                rs.getString("input_payload"), rs.getString("output_payload"),
                rs.getString("error_message"), rs.getString("created_by"),
                inst(rs.getTimestamp("created_at")), inst(rs.getTimestamp("updated_at")));
    }

    private StepRunRecord mapStep(ResultSet rs, int i) throws SQLException {
        return new StepRunRecord(
                rs.getString("id"), rs.getString("run_id"), rs.getString("step_id"),
                rs.getString("step_type"), rs.getString("status"),
                rs.getString("input_payload"), rs.getString("output_payload"),
                inst(rs.getTimestamp("started_at")), inst(rs.getTimestamp("finished_at")));
    }

    @Override
    public void saveWorkflow(WorkflowRecord r) {
        jdbc.update("""
                INSERT INTO workflow_def
                  (tenant_id, workflow_id, name, description, definition, status, created_at, updated_at)
                VALUES (:tenantId, :workflowId, :name, :description, :definition, :status, :createdAt, :updatedAt)
                ON CONFLICT (tenant_id, workflow_id) DO UPDATE SET
                  name = EXCLUDED.name,
                  description = EXCLUDED.description,
                  definition = EXCLUDED.definition,
                  status = EXCLUDED.status,
                  updated_at = EXCLUDED.updated_at
                """,
                new MapSqlParameterSource()
                        .addValue("tenantId", r.tenantId())
                        .addValue("workflowId", r.workflowId())
                        .addValue("name", r.name())
                        .addValue("description", r.description())
                        .addValue("definition", r.definitionJson())
                        .addValue("status", r.status())
                        .addValue("createdAt", ts(r.createdAt() == null ? Instant.now() : r.createdAt()))
                        .addValue("updatedAt", ts(r.updatedAt() == null ? Instant.now() : r.updatedAt())));
    }

    @Override
    public Optional<WorkflowRecord> findWorkflow(String tenantId, String workflowId) {
        List<WorkflowRecord> rows = jdbc.query("""
                SELECT tenant_id, workflow_id, name, description, definition, status, created_at, updated_at
                FROM workflow_def WHERE tenant_id = :tenantId AND workflow_id = :workflowId
                """,
                new MapSqlParameterSource().addValue("tenantId", tenantId).addValue("workflowId", workflowId),
                this::mapWorkflow);
        return rows.stream().findFirst();
    }

    @Override
    public List<WorkflowRecord> listWorkflows(String tenantId) {
        return jdbc.query("""
                SELECT tenant_id, workflow_id, name, description, definition, status, created_at, updated_at
                FROM workflow_def WHERE tenant_id = :tenantId ORDER BY workflow_id
                """,
                new MapSqlParameterSource().addValue("tenantId", tenantId),
                this::mapWorkflow);
    }

    @Override
    public void saveRevision(RevisionRecord r) {
        jdbc.update("""
                INSERT INTO workflow_revision
                  (tenant_id, workflow_id, revision, definition, status, published_at)
                VALUES (:tenantId, :workflowId, :revision, :definition, :status, :publishedAt)
                ON CONFLICT (tenant_id, workflow_id, revision) DO UPDATE SET
                  definition = EXCLUDED.definition,
                  status = EXCLUDED.status,
                  published_at = EXCLUDED.published_at
                """,
                new MapSqlParameterSource()
                        .addValue("tenantId", r.tenantId())
                        .addValue("workflowId", r.workflowId())
                        .addValue("revision", r.revision())
                        .addValue("definition", r.definitionJson())
                        .addValue("status", r.status())
                        .addValue("publishedAt", ts(r.publishedAt() == null ? Instant.now() : r.publishedAt())));
    }

    @Override
    public Optional<RevisionRecord> findRevision(String tenantId, String workflowId, int revision) {
        List<RevisionRecord> rows = jdbc.query("""
                SELECT tenant_id, workflow_id, revision, definition, status, published_at
                FROM workflow_revision
                WHERE tenant_id = :tenantId AND workflow_id = :workflowId AND revision = :revision
                """,
                new MapSqlParameterSource()
                        .addValue("tenantId", tenantId)
                        .addValue("workflowId", workflowId)
                        .addValue("revision", revision),
                this::mapRevision);
        return rows.stream().findFirst();
    }

    @Override
    public Optional<RevisionRecord> findLatestPublishedRevision(String tenantId, String workflowId) {
        List<RevisionRecord> rows = jdbc.query("""
                SELECT tenant_id, workflow_id, revision, definition, status, published_at
                FROM workflow_revision
                WHERE tenant_id = :tenantId AND workflow_id = :workflowId AND status = 'PUBLISHED'
                ORDER BY revision DESC LIMIT 1
                """,
                new MapSqlParameterSource()
                        .addValue("tenantId", tenantId)
                        .addValue("workflowId", workflowId),
                this::mapRevision);
        return rows.stream().findFirst();
    }

    @Override
    public int nextRevisionNumber(String tenantId, String workflowId) {
        Integer max = jdbc.queryForObject("""
                SELECT COALESCE(MAX(revision), 0) FROM workflow_revision
                WHERE tenant_id = :tenantId AND workflow_id = :workflowId
                """,
                new MapSqlParameterSource()
                        .addValue("tenantId", tenantId)
                        .addValue("workflowId", workflowId),
                Integer.class);
        return (max == null ? 0 : max) + 1;
    }

    @Override
    public void saveRun(RunRecord r) {
        jdbc.update("""
                INSERT INTO workflow_run
                  (id, tenant_id, workflow_id, revision, status, current_step, input_payload,
                   output_payload, error_message, created_by, created_at, updated_at)
                VALUES (:id, :tenantId, :workflowId, :revision, :status, :currentStep, :inputPayload,
                        :outputPayload, :errorMessage, :createdBy, :createdAt, :updatedAt)
                ON CONFLICT (id) DO UPDATE SET
                  status = EXCLUDED.status,
                  current_step = EXCLUDED.current_step,
                  input_payload = EXCLUDED.input_payload,
                  output_payload = EXCLUDED.output_payload,
                  error_message = EXCLUDED.error_message,
                  updated_at = EXCLUDED.updated_at
                """,
                new MapSqlParameterSource()
                        .addValue("id", r.id())
                        .addValue("tenantId", r.tenantId())
                        .addValue("workflowId", r.workflowId())
                        .addValue("revision", r.revision())
                        .addValue("status", r.status())
                        .addValue("currentStep", r.currentStep())
                        .addValue("inputPayload", r.inputPayload())
                        .addValue("outputPayload", r.outputPayload())
                        .addValue("errorMessage", r.errorMessage())
                        .addValue("createdBy", r.createdBy())
                        .addValue("createdAt", ts(r.createdAt() == null ? Instant.now() : r.createdAt()))
                        .addValue("updatedAt", ts(r.updatedAt() == null ? Instant.now() : r.updatedAt())));
    }

    @Override
    public Optional<RunRecord> findRun(String runId) {
        List<RunRecord> rows = jdbc.query("""
                SELECT id, tenant_id, workflow_id, revision, status, current_step, input_payload,
                       output_payload, error_message, created_by, created_at, updated_at
                FROM workflow_run WHERE id = :id
                """,
                new MapSqlParameterSource().addValue("id", runId),
                this::mapRun);
        return rows.stream().findFirst();
    }

    @Override
    public List<RunRecord> listRuns(String tenantId, int limit) {
        return jdbc.query("""
                SELECT id, tenant_id, workflow_id, revision, status, current_step, input_payload,
                       output_payload, error_message, created_by, created_at, updated_at
                FROM workflow_run WHERE tenant_id = :tenantId
                ORDER BY created_at DESC LIMIT :limit
                """,
                new MapSqlParameterSource().addValue("tenantId", tenantId).addValue("limit", limit),
                this::mapRun);
    }

    @Override
    public void saveStepRun(StepRunRecord r) {
        jdbc.update("""
                INSERT INTO workflow_step_run
                  (id, run_id, step_id, step_type, status, input_payload, output_payload, started_at, finished_at)
                VALUES (:id, :runId, :stepId, :stepType, :status, :inputPayload, :outputPayload, :startedAt, :finishedAt)
                ON CONFLICT (id) DO UPDATE SET
                  status = EXCLUDED.status,
                  output_payload = EXCLUDED.output_payload,
                  finished_at = EXCLUDED.finished_at
                """,
                new MapSqlParameterSource()
                        .addValue("id", r.id())
                        .addValue("runId", r.runId())
                        .addValue("stepId", r.stepId())
                        .addValue("stepType", r.stepType())
                        .addValue("status", r.status())
                        .addValue("inputPayload", r.inputPayload())
                        .addValue("outputPayload", r.outputPayload())
                        .addValue("startedAt", ts(r.startedAt()))
                        .addValue("finishedAt", ts(r.finishedAt())));
    }

    @Override
    public List<StepRunRecord> listStepRuns(String runId) {
        return jdbc.query("""
                SELECT id, run_id, step_id, step_type, status, input_payload, output_payload, started_at, finished_at
                FROM workflow_step_run WHERE run_id = :runId ORDER BY started_at, id
                """,
                new MapSqlParameterSource().addValue("runId", runId),
                this::mapStep);
    }
}
