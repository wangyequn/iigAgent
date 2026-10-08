package com.sinosoft.amp.openapi.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sinosoft.amp.openapi.agent.CatalogRuntimeService;
import com.sinosoft.amp.openapi.billing.QuotaService;
import com.sinosoft.amp.openapi.catalog.AgentCatalogRepository;
import com.sinosoft.amp.openapi.governance.GovernanceService;
import com.sinosoft.amp.openapi.knowledge.KnowledgeService;
import com.sinosoft.amp.openapi.ops.AuditLogService;
import com.sinosoft.amp.openapi.ops.PlatformMetrics;
import com.sinosoft.amp.openapi.ops.ReadinessSupport;
import com.sinosoft.amp.openapi.ops.RuntimeConfigService;
import com.sinosoft.amp.openapi.orchestration.TeamService;
import com.sinosoft.amp.openapi.orchestration.WorkflowService;
import com.sinosoft.amp.openapi.skill.SkillCatalogRepository;

/**
 * 控制台智能机器人（console-bot）所需的服务聚合。
 *
 * <p>刻意只依赖**底层仓储/无实例缓存**的 Service，避免
 * AgentInstanceFactory → ConsoleBotSupport → CatalogRuntimeService/SkillAdminService
 * → AgentInstanceCache → AgentInstanceFactory 的循环依赖。
 * 工具在进程内直调这些组件，不二次鉴权。
 */
public class ConsoleBotSupport {

    private final AgentCatalogRepository catalog;
    private final GovernanceService governance;
    private final KnowledgeService knowledge;
    private final SkillCatalogRepository skills;
    private final WorkflowService workflows;
    private final TeamService teams;
    private final QuotaService quota;
    private final AuditLogService audit;
    private final ReadinessSupport readiness;
    private final PlatformMetrics metrics;
    private final RuntimeConfigService config;
    private final ObjectMapper mapper = new ObjectMapper();

    public ConsoleBotSupport(
            AgentCatalogRepository catalog,
            GovernanceService governance,
            KnowledgeService knowledge,
            SkillCatalogRepository skills,
            WorkflowService workflows,
            TeamService teams,
            QuotaService quota,
            AuditLogService audit,
            ReadinessSupport readiness,
            PlatformMetrics metrics,
            RuntimeConfigService config) {
        this.catalog = catalog;
        this.governance = governance;
        this.knowledge = knowledge;
        this.skills = skills;
        this.workflows = workflows;
        this.teams = teams;
        this.quota = quota;
        this.audit = audit;
        this.readiness = readiness;
        this.metrics = metrics;
        this.config = config;
    }

    public AgentCatalogRepository catalog() {
        return catalog;
    }

    public GovernanceService governance() {
        return governance;
    }

    public KnowledgeService knowledge() {
        return knowledge;
    }

    public SkillCatalogRepository skills() {
        return skills;
    }

    public WorkflowService workflows() {
        return workflows;
    }

    public TeamService teams() {
        return teams;
    }

    public QuotaService quota() {
        return quota;
    }

    public AuditLogService audit() {
        return audit;
    }

    public ReadinessSupport readiness() {
        return readiness;
    }

    public PlatformMetrics metrics() {
        return metrics;
    }

    public RuntimeConfigService config() {
        return config;
    }

    public ObjectMapper mapper() {
        return mapper;
    }
}
