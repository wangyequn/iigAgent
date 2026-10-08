package com.sinosoft.amp.openapi.orchestration;

import com.sinosoft.amp.openapi.security.AuthContext;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Team 最小集：注册与成员管理；调度对接 AgentScope Service 留待后续。 */
@Service
public class TeamService {

    private final NamedParameterJdbcTemplate jdbc;

    public TeamService(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Map<String, Object> upsertTeam(String teamId, String name, String leaderAgentId,
                                          String description, List<String> memberAgentIds) {
        String tenant = AuthContext.requireTenantId();
        jdbc.update("""
                INSERT INTO team (tenant_id, team_id, name, leader_agent_id, description, status, created_at)
                VALUES (:tenantId, :teamId, :name, :leader, :description, 'ACTIVE', :at)
                ON CONFLICT (tenant_id, team_id) DO UPDATE SET
                  name = EXCLUDED.name,
                  leader_agent_id = EXCLUDED.leader_agent_id,
                  description = EXCLUDED.description
                """,
                new MapSqlParameterSource()
                        .addValue("tenantId", tenant)
                        .addValue("teamId", teamId)
                        .addValue("name", name)
                        .addValue("leader", leaderAgentId)
                        .addValue("description", description)
                        .addValue("at", Timestamp.from(Instant.now())));
        if (memberAgentIds != null) {
            for (String agentId : memberAgentIds) {
                jdbc.update("""
                        INSERT INTO team_member (tenant_id, team_id, agent_id, role_name, created_at)
                        VALUES (:tenantId, :teamId, :agentId, 'member', :at)
                        ON CONFLICT (tenant_id, team_id, agent_id) DO NOTHING
                        """,
                        new MapSqlParameterSource()
                                .addValue("tenantId", tenant)
                                .addValue("teamId", teamId)
                                .addValue("agentId", agentId)
                                .addValue("at", Timestamp.from(Instant.now())));
            }
        }
        return getTeam(teamId);
    }

    public Map<String, Object> getTeam(String teamId) {
        String tenant = AuthContext.requireTenantId();
        List<Map<String, Object>> teams = jdbc.queryForList("""
                SELECT team_id, name, leader_agent_id, description, status, created_at
                FROM team WHERE tenant_id = :tenantId AND team_id = :teamId
                """,
                new MapSqlParameterSource().addValue("tenantId", tenant).addValue("teamId", teamId));
        if (teams.isEmpty()) {
            throw new OrchestrationException("TEAM_NOT_FOUND", "team not found: " + teamId);
        }
        Map<String, Object> m = new LinkedHashMap<>(teams.get(0));
        m.put("members", jdbc.queryForList("""
                SELECT agent_id, role_name FROM team_member
                WHERE tenant_id = :tenantId AND team_id = :teamId ORDER BY agent_id
                """,
                new MapSqlParameterSource().addValue("tenantId", tenant).addValue("teamId", teamId)));
        return m;
    }

    public List<Map<String, Object>> listTeams() {
        String tenant = AuthContext.requireTenantId();
        return jdbc.queryForList("""
                SELECT team_id, name, leader_agent_id, description, status, created_at
                FROM team WHERE tenant_id = :tenantId ORDER BY team_id
                """,
                new MapSqlParameterSource().addValue("tenantId", tenant));
    }

    /** 团队任务下发占位：登记一条带 team 标签的 workflow run（后续接 Service Issue/Run）。 */
    public Map<String, Object> createTask(String teamId, String goal) {
        String tenant = AuthContext.requireTenantId();
        getTeam(teamId);
        String taskId = UUID.randomUUID().toString().replace("-", "");
        // 仅落审计语义的占位 map；真实调度 S13+
        return Map.of(
                "taskId", taskId,
                "teamId", teamId,
                "tenantId", tenant,
                "goal", goal == null ? "" : goal,
                "status", "ACCEPTED",
                "engine", "local-placeholder");
    }
}
