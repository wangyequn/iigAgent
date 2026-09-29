package com.sinosoft.amp.openapi.orchestration;

import java.util.List;
import java.util.Map;

/** Workflow 定义：有序步骤（固定步骤最小实现；复杂编排对接 AgentScope Service）。 */
public record WorkflowDefinition(List<WorkflowStep> steps) {

    public WorkflowDefinition {
        if (steps == null || steps.isEmpty()) {
            throw new OrchestrationException("WORKFLOW_STEPS_REQUIRED", "workflow must contain at least one step");
        }
    }

    public WorkflowStep requireStep(String stepId) {
        return steps.stream()
                .filter(s -> s.id().equals(stepId))
                .findFirst()
                .orElseThrow(() -> new OrchestrationException(
                        "WORKFLOW_STEP_NOT_FOUND", "step not found: " + stepId));
    }

    public WorkflowStep firstStep() {
        return steps.get(0);
    }

    public record WorkflowStep(
            String id,
            String type,
            String agentId,
            String input,
            String prompt,
            String next) {

        public static final String TYPE_ECHO = "ECHO";
        public static final String TYPE_AGENT = "AGENT";
        public static final String TYPE_HUMAN_GATE = "HUMAN_GATE";

        public WorkflowStep {
            if (id == null || id.isBlank()) {
                throw new OrchestrationException("WORKFLOW_STEP_ID_REQUIRED", "step id is required");
            }
            if (type == null || type.isBlank()) {
                throw new OrchestrationException("WORKFLOW_STEP_TYPE_REQUIRED", "step type is required: " + id);
            }
            String t = type.toUpperCase(java.util.Locale.ROOT);
            if (!TYPE_ECHO.equals(t) && !TYPE_AGENT.equals(t) && !TYPE_HUMAN_GATE.equals(t)) {
                throw new OrchestrationException("WORKFLOW_STEP_TYPE_INVALID",
                        "unsupported step type: " + type + " (expected ECHO|AGENT|HUMAN_GATE)");
            }
        }

        public String normalizedType() {
            return type.toUpperCase(java.util.Locale.ROOT);
        }

        public boolean isTerminal() {
            return next == null || next.isBlank();
        }
    }

    public static WorkflowDefinition parse(Map<String, Object> raw) {
        if (raw == null) {
            throw new OrchestrationException("WORKFLOW_DEFINITION_INVALID", "definition is required");
        }
        Object stepsObj = raw.get("steps");
        if (!(stepsObj instanceof List<?> list) || list.isEmpty()) {
            throw new OrchestrationException("WORKFLOW_STEPS_REQUIRED", "definition.steps must be a non-empty array");
        }
        List<WorkflowStep> steps = new java.util.ArrayList<>();
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> m)) {
                throw new OrchestrationException("WORKFLOW_DEFINITION_INVALID", "each step must be an object");
            }
            steps.add(new WorkflowStep(
                    str(m.get("id")),
                    str(m.get("type")),
                    str(m.get("agentId")),
                    str(m.get("input")),
                    str(m.get("prompt")),
                    str(m.get("next"))));
        }
        return new WorkflowDefinition(List.copyOf(steps));
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
