package com.sinosoft.amp.openapi.web;

import com.sinosoft.amp.openapi.iam.RbacGuard;
import com.sinosoft.amp.openapi.skill.SkillRolloutService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** S15 技能灰度：CANARY / GA / ROLLBACK。 */
@RestController
@RequestMapping("/api/v1/admin/skills")
public class SkillRolloutController {

    private final SkillRolloutService rollouts;
    private final RbacGuard rbac;

    public SkillRolloutController(SkillRolloutService rollouts, RbacGuard rbac) {
        this.rollouts = rollouts;
        this.rbac = rbac;
    }

    @GetMapping("/rollouts")
    public List<Map<String, Object>> list() {
        rbac.require("skill:review");
        return rollouts.list();
    }

    @PostMapping("/{skillId}/rollout/canary")
    public Map<String, Object> canary(@PathVariable String skillId, @RequestBody CanaryRequest body) {
        rbac.require("skill:review");
        int pct = body == null || body.percent() == null ? 10 : body.percent();
        return rollouts.startCanary(skillId, pct, body == null ? null : body.env(),
                body == null ? null : body.tenantWhitelist());
    }

    @PostMapping("/{skillId}/rollout/ga")
    public Map<String, Object> ga(@PathVariable String skillId) {
        rbac.require("skill:review");
        return rollouts.promoteGa(skillId);
    }

    @PostMapping("/{skillId}/rollout/rollback")
    public Map<String, Object> rollback(@PathVariable String skillId) {
        rbac.require("skill:review");
        return rollouts.rollback(skillId);
    }

    public record CanaryRequest(Integer percent, String env, String tenantWhitelist) {
    }
}
