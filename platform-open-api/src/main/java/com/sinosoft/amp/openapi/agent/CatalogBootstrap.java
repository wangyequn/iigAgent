package com.sinosoft.amp.openapi.agent;

import com.sinosoft.amp.openapi.catalog.AgentCatalogRepository;
import com.sinosoft.amp.openapi.catalog.AgentRecord;
import com.sinosoft.amp.openapi.catalog.AgentStatus;
import com.sinosoft.amp.openapi.catalog.AgentVersionRecord;
import com.sinosoft.amp.openapi.catalog.VersionStatus;
import com.sinosoft.amp.openapi.governance.GovernanceRepository;
import com.sinosoft.amp.openapi.governance.GovernanceService;
import com.sinosoft.amp.openapi.governance.PermissionRuleRecord;
import com.sinosoft.amp.openapi.tool.BusinessToolRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * 目录 + S4 治理种子：demo v1 + 权限规则 + 业务工具登记。
 */
@Component
public class CatalogBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(CatalogBootstrap.class);

    private final AgentCatalogRepository catalog;
    private final AgentProperties properties;
    private final String defaultTenantId;
    private final GovernanceService governance;
    private final GovernanceRepository governanceRepository;
    private final com.sinosoft.amp.openapi.skill.SkillCatalogRepository skillCatalog;
    private final com.sinosoft.amp.openapi.skill.SkillAdminService skillAdmin;
    private final com.sinosoft.amp.openapi.knowledge.KnowledgeService knowledge;
    private final com.sinosoft.amp.openapi.ops.ReadinessSupport readiness;
    private final com.sinosoft.amp.openapi.ops.NacosConfigBridge nacosConfigBridge;
    private final com.sinosoft.amp.openapi.ops.OtelBridge otelBridge;

    public CatalogBootstrap(
            AgentCatalogRepository catalog,
            AgentProperties properties,
            @Value("${amp.auth.keys[0].tenant-id:acme}") String defaultTenantId,
            GovernanceService governance,
            GovernanceRepository governanceRepository,
            com.sinosoft.amp.openapi.skill.SkillCatalogRepository skillCatalog,
            com.sinosoft.amp.openapi.skill.SkillAdminService skillAdmin,
            com.sinosoft.amp.openapi.knowledge.KnowledgeService knowledge,
            com.sinosoft.amp.openapi.ops.ReadinessSupport readiness,
            com.sinosoft.amp.openapi.ops.NacosConfigBridge nacosConfigBridge,
            com.sinosoft.amp.openapi.ops.OtelBridge otelBridge) {
        this.catalog = catalog;
        this.properties = properties;
        this.defaultTenantId = defaultTenantId;
        this.governance = governance;
        this.governanceRepository = governanceRepository;
        this.skillCatalog = skillCatalog;
        this.skillAdmin = skillAdmin;
        this.knowledge = knowledge;
        this.readiness = readiness;
        this.nacosConfigBridge = nacosConfigBridge;
        this.otelBridge = otelBridge;
    }

    @Override
    public void run(ApplicationArguments args) {
        readiness.logStartupCheck();
        nacosConfigBridge.start();
        otelBridge.start();
        String tenantId = defaultTenantId == null || defaultTenantId.isBlank() ? "acme" : defaultTenantId;
        String agentId = properties.name() == null || properties.name().isBlank() ? "demo" : properties.name();
        System.setProperty("amp.default.tenant", tenantId);

        if (catalog.findAgent(tenantId, agentId).isEmpty()) {
            catalog.upsertAgent(new AgentRecord(
                    tenantId, agentId, agentId, AgentStatus.ACTIVE, Instant.now()));
            log.info("seed agent tenant={} agentId={}", tenantId, agentId);
        }

        if (catalog.findVersion(tenantId, agentId, 1).isEmpty()) {
            // workspace 留空 → 运行时按 {base}/{tenant}/v{version} 组合（租户隔离）
            catalog.upsertVersion(new AgentVersionRecord(
                    tenantId, agentId, 1, VersionStatus.PUBLISHED,
                    properties.modelId(), properties.sysPrompt(), null,
                    Instant.now(), Instant.now()));
            log.info("seed agent_version tenant={} agentId={} v1", tenantId, agentId);
        }

        if (catalog.findRelease(tenantId, agentId).isEmpty()) {
            catalog.upsertRelease(tenantId, agentId, 1);
            log.info("seed endpoint release tenant={} agentId={} -> v1", tenantId, agentId);
        }

        seedGovernance(tenantId, agentId);

        List<AgentVersionRecord> versions = catalog.listVersions(tenantId, agentId);
        log.info("catalog ready tenant={} agentId={} versions={} release={}",
                tenantId, agentId, versions.size(),
                catalog.findRelease(tenantId, agentId).map(r -> r.releaseVersion()).orElse(-1));
    }

    private void seedGovernance(String tenantId, String agentId) {
        governance.setMode(tenantId, agentId, "DEFAULT");

        upsertTool(tenantId, "query_orders", "查询订单列表（只读）", "IN_PROCESS");
        upsertTool(tenantId, "place_order", "创建订单（危险写操作，需 HITL 确认）", "IN_PROCESS");

        seedRuleIfAbsent(tenantId, agentId, "place_order", "ASK");
        seedRuleIfAbsent(tenantId, agentId, "query_orders", "ALLOW");
        seedRuleIfAbsent(tenantId, agentId, "knowledge_search", "ALLOW");
        // 不再播种 memory_search/session_search/memory_get/read_file 的 ALLOW
        // 工具面默认 disableMemoryTools + disableFilesystemTools
        // （工具清单已证实未注册）。若未来开启对应工具，需在此重新播种。
        seedDraftSkill(tenantId);
        seedKnowledge(tenantId);
    }

    private void seedKnowledge(String tenantId) {
        if (knowledge == null) {
            return;
        }
        try {
            if (!knowledge.listDocuments(tenantId).isEmpty()) {
                return;
            }
            knowledge.ingest(tenantId, "AI中台订单规范", "bootstrap",
                    """
                    # 订单规范

                    下单必须使用 place_order 工具，并包含 product 与 quantity 字段。
                    quantity 必须是正整数。危险写操作需要 HITL 人工确认。
                    查询订单使用 query_orders 只读工具，可传 orderId。
                    客服话术：提及引用片段编号，例如 [文档#切片]，便于审计追溯。
                    """);
            log.info("seed knowledge doc tenant={} provider={}", tenantId, knowledge.providerId());
        } catch (RuntimeException e) {
            // 外部知识库（如 Dify）不可用时不阻断启动
            log.warn("seed knowledge skipped tenant={}: {}", tenantId, e.toString());
        }
    }

    private void seedDraftSkill(String tenantId) {
        if (skillCatalog == null) {
            return;
        }
        boolean exists = skillCatalog.findByNameVersion(tenantId, "order-style", "1.0.0").isPresent()
                || skillCatalog.list(tenantId).stream().anyMatch(s -> "order-style".equals(s.name()));
        if (exists) {
            return;
        }
        skillAdmin.upload(tenantId, "order-style", "1.0.0",
                "订单话术与下单规范（草稿，需审核后可见）",
                """
                # order-style

                ## 何时使用
                用户要求下单、查订单时参考本技能。
                ## 下单规范
                1. 必须使用工具 place_order(product, quantity)
                2. quantity 为正整数
                3. place_order 需 HITL 确认，不得跳过
                ## 查询
                使用 query_orders(orderId?) 只读查询。
                """,
                "bootstrap");
        log.info("seed skill draft order-style tenant={}", tenantId);
    }

    private void seedRuleIfAbsent(String tenantId, String agentId, String tool, String behavior) {
        // 按「工具级」判存在：upsertRule 是同工具行为替换语义，若按 tool+behavior 判，
        // 用户经 acceptRule 接受 ALLOW 后，重启时种子发现无 ASK 行会补种 ASK 并覆盖 ALLOW。
        boolean has = governance.listRules(tenantId, agentId).stream()
                .anyMatch(r -> tool.equals(r.toolName()));
        if (!has) {
            governance.upsertRule(tenantId, agentId, tool, behavior, "*", "platform");
            log.info("seed permission {} for {} tenant={} agent={}", behavior, tool, tenantId, agentId);
        }
    }

    private void upsertTool(String tenantId, String name, String desc, String kind) {
        boolean exists = governance.listTools(tenantId).stream().anyMatch(t -> t.toolName().equals(name));
        if (!exists) {
            governance.registerTool(tenantId, name, desc, kind, null);
            log.info("seed business tool {} tenant={}", name, tenantId);
        }
    }
}
