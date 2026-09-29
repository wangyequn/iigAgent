package com.sinosoft.amp.openapi.web;

import com.sinosoft.amp.openapi.iam.RbacGuard;
import com.sinosoft.amp.openapi.orchestration.TeamService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/teams")
public class TeamController {

    private final TeamService teams;
    private final RbacGuard rbac;

    public TeamController(TeamService teams, RbacGuard rbac) {
        this.teams = teams;
        this.rbac = rbac;
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        rbac.require("orch:read");
        return teams.listTeams();
    }

    @GetMapping("/{teamId}")
    public Map<String, Object> get(@PathVariable String teamId) {
        rbac.require("orch:read");
        return teams.getTeam(teamId);
    }

    @PutMapping("/{teamId}")
    public Map<String, Object> upsert(
            @PathVariable String teamId,
            @RequestBody UpsertTeamRequest request) {
        rbac.require("orch:write");
        return teams.upsertTeam(
                teamId,
                request.name() == null ? teamId : request.name(),
                request.leaderAgentId(),
                request.description(),
                request.memberAgentIds());
    }

    @PostMapping("/{teamId}/tasks")
    public Map<String, Object> createTask(
            @PathVariable String teamId,
            @RequestBody TaskRequest request) {
        rbac.require("orch:run");
        String goal = request == null ? null : request.goal();
        return teams.createTask(teamId, goal);
    }

    public record UpsertTeamRequest(String name, String leaderAgentId, String description, List<String> memberAgentIds) {
    }

    public record TaskRequest(String goal) {
    }
}
