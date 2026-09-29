package com.sinosoft.amp.openapi.web;

import com.sinosoft.amp.openapi.automation.AutomationService;
import com.sinosoft.amp.openapi.channel.ChannelBindingService;
import com.sinosoft.amp.openapi.iam.RbacGuard;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** S13：Channel 绑定 + Automation 管理/触发。 */
@RestController
@RequestMapping("/api/v1")
public class AdminChannelAutomationController {

    private final ChannelBindingService bindings;
    private final AutomationService automations;
    private final RbacGuard rbac;

    public AdminChannelAutomationController(
            ChannelBindingService bindings, AutomationService automations, RbacGuard rbac) {
        this.bindings = bindings;
        this.automations = automations;
        this.rbac = rbac;
    }

    @GetMapping("/admin/channel-bindings")
    public List<Map<String, Object>> listBindings() {
        rbac.require("admin:read");
        return bindings.list();
    }

    @PutMapping("/admin/channel-bindings/{channelType}/{agentId}")
    public Map<String, Object> upsertBinding(
            @PathVariable String channelType,
            @PathVariable String agentId,
            @RequestBody BindingRequest body) {
        rbac.require("admin:write");
        return bindings.upsert(channelType, agentId, body.config(), body.secret());
    }

    @GetMapping("/admin/automations")
    public List<Map<String, Object>> listAutomations() {
        rbac.require("admin:read");
        return automations.list();
    }

    @PostMapping("/admin/automations")
    public Map<String, Object> createAutomation(@RequestBody CreateAutomationRequest body) {
        rbac.require("admin:write");
        return automations.create(
                body.name(), body.type(), body.cronExpr(), body.secret(),
                body.targetWorkflowId(), body.payload());
    }

    @DeleteMapping("/admin/automations/{id}")
    public Map<String, Object> disableAutomation(@PathVariable String id) {
        rbac.require("admin:write");
        automations.disable(id);
        return Map.of("id", id, "status", "DISABLED");
    }

    /** Webhook 公开触发（secret 校验），不走 API Key。 */
    @PostMapping("/automations/{id}/webhook")
    public Map<String, Object> fireWebhook(
            @PathVariable String id,
            @RequestHeader(value = "X-Automation-Secret", required = false) String secret,
            @RequestBody(required = false) String body) {
        return automations.fireWebhook(id, secret, body == null ? "" : body);
    }

    public record BindingRequest(String config, String secret) {
    }

    public record CreateAutomationRequest(
            String name, String type, String cronExpr, String secret,
            String targetWorkflowId, String payload) {
    }
}
