package com.sinosoft.amp.openapi.web;

import com.sinosoft.amp.openapi.iam.RbacGuard;
import com.sinosoft.amp.openapi.orchestration.WorkflowService;
import com.sinosoft.amp.openapi.security.AuthContext;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** Phase2 编排 API：Workflow 定义发布、Run 触发与查询。 */
@RestController
@RequestMapping("/api/v1")
public class WorkflowController {

    private final WorkflowService workflows;
    private final com.sinosoft.amp.openapi.orchestration.RunEventBus runEvents;
    private final RbacGuard rbac;

    public WorkflowController(
            WorkflowService workflows,
            com.sinosoft.amp.openapi.orchestration.RunEventBus runEvents,
            RbacGuard rbac) {
        this.workflows = workflows;
        this.runEvents = runEvents;
        this.rbac = rbac;
    }

    @GetMapping("/runs/{runId}/events")
    public List<Map<String, Object>> listRunEvents(@PathVariable String runId) {
        rbac.require("orch:read");
        workflows.getRun(runId);
        return runEvents.list(runId).stream().map(e -> {
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("type", e.type());
            m.put("stepId", e.stepId());
            m.put("message", e.message());
            m.put("at", e.at());
            m.put("data", e.data());
            return m;
        }).toList();
    }

    @GetMapping("/workflows")
    public List<Map<String, Object>> list() {
        rbac.require("orch:read");
        return workflows.listWorkflows();
    }

    @PutMapping("/workflows/{workflowId}")
    public Map<String, Object> upsert(
            @PathVariable String workflowId,
            @Valid @RequestBody UpsertWorkflowRequest request) {
        rbac.require("orch:write");
        return workflows.upsertWorkflow(
                workflowId, request.name(), request.description(), request.definition());
    }

    @PostMapping("/workflows/{workflowId}/revisions")
    public Map<String, Object> publish(@PathVariable String workflowId) {
        rbac.require("orch:write");
        return workflows.publishRevision(workflowId);
    }

    @PostMapping("/workflows/{workflowId}/runs")
    public Map<String, Object> startRun(
            @PathVariable String workflowId,
            @Valid @RequestBody StartRunRequest request) {
        rbac.require("orch:run");
        return workflows.startRun(
                workflowId,
                request.revision(),
                request.input(),
                request.userId() == null ? AuthContext.actorLabel() : request.userId());
    }

    @GetMapping("/runs/{runId}")
    public Map<String, Object> getRun(@PathVariable String runId) {
        rbac.require("orch:read");
        return workflows.getRun(runId);
    }

    @GetMapping("/runs")
    public List<Map<String, Object>> listRuns(@RequestParam(defaultValue = "50") int limit) {
        rbac.require("orch:read");
        return workflows.listRuns(limit);
    }

    @PostMapping("/runs/{runId}/confirm")
    public Map<String, Object> confirm(
            @PathVariable String runId,
            @RequestBody ConfirmRunRequest request) {
        rbac.require("orch:run");
        boolean approved = request != null && Boolean.TRUE.equals(request.approved());
        String note = request == null ? null : request.note();
        return workflows.confirmRun(runId, approved, note);
    }

    @PostMapping("/runs/{runId}/cancel")
    public Map<String, Object> cancel(@PathVariable String runId) {
        rbac.require("orch:run");
        return workflows.cancelRun(runId);
    }

    public record UpsertWorkflowRequest(String name, String description, Map<String, Object> definition) {
    }

    public record StartRunRequest(Integer revision, Map<String, Object> input, String userId) {
    }

    public record ConfirmRunRequest(Boolean approved, String note) {
    }
}
