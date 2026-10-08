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
    private final com.sinosoft.amp.openapi.ops.NacosAiSyncService nacosAiSync;
    private final com.sinosoft.amp.openapi.ops.NacosAiProperties nacosAiProperties;

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
            com.sinosoft.amp.openapi.ops.OtelBridge otelBridge,
            com.sinosoft.amp.openapi.ops.NacosAiSyncService nacosAiSync,
            com.sinosoft.amp.openapi.ops.NacosAiProperties nacosAiProperties) {
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
        this.nacosAiSync = nacosAiSync;
        this.nacosAiProperties = nacosAiProperties;
    }

    @Override
    public void run(ApplicationArguments args) {
        readiness.logStartupCheck();
        nacosConfigBridge.start();
        otelBridge.start();
        maybeSyncNacosAi();
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
        seedConsoleBot(tenantId);

        List<AgentVersionRecord> versions = catalog.listVersions(tenantId, agentId);
        log.info("catalog ready tenant={} agentId={} versions={} release={}",
                tenantId, agentId, versions.size(),
                catalog.findRelease(tenantId, agentId).map(r -> r.releaseVersion()).orElse(-1));
    }

    /** 右下角智能机器人：console-bot 播种（Agent + v1 + 发布指针 + 权限）。 */
    private void seedConsoleBot(String tenantId) {
        String botId = "console-bot";
        String prompt = """
                你是 AI 中台控制台的智能机器人「小助」，内嵌在控制台右下角。你本身是一个标准智能体（agentId=console-bot），
                具备本系统全部能力，并与控制台 UI 深度联动。

                ## 身份与语气
                - 中文优先，简洁、专业、可执行；不要空话。
                - 用户问平台问题时，优先调用工具拿实时数据，不要编造。
                - 回答后按需给出下一步建议（可点击操作）。

                ## 能力地图（工具）
                平台状态：platform_status
                Agent：list_agents, agent_detail
                治理：list_permissions, list_business_tools
                技能/知识：list_skills, list_knowledge, console_knowledge_search（知识问题必须检索并引用）
                编排：list_workflows, list_teams
                运营：usage_summary, list_audit
                UI 交互：ui_navigate, ui_focus, ui_notify, ui_ask_user, ui_suggest
                通用：safe_lookup（只读）、dangerous_write（危险写，需 HITL）、knowledge_search

                ## UI 深度交互协议（关键）
                当需要驱动控制台界面时，调用 UI 工具，工具会返回 ui.actions，前端自动执行：
                - 要跳页面 → ui_navigate(path)。常用路径：
                  /workbench/chat 智能对话 | /workbench/approvals 审批 | /workbench/knowledge 知识库
                  /agents Agent列表 | /skills 技能 | /workflows 编排 | /teams 团队
                  /governance/permissions 权限 | /governance/tools 工具 | /governance/channels 渠道
                  /governance/external External | /governance/iam IAM
                  /ops/overview 概览 | /ops/sessions 会话 | /ops/billing 用量 | /ops/audit 审计
                  /ops/sandbox 沙箱 | /ops/credentials 凭证
                  /system/health 健康 | /system/config 配置 | /system/openai 调试
                - 要打开实体详情 → ui_focus(entityType, entityId)
                - 要提醒用户 → ui_notify(level, message)
                - 要追问 → ui_ask_user(prompt, options)
                - 要给快捷操作 → ui_suggest(actions JSON)

                ## 典型任务
                1. 「帮我看看系统状态」→ platform_status + 可选 usage_summary，摘要并 ui_suggest 后续
                2. 「有哪些 Agent」→ list_agents，摘要后 ui_suggest 查看详情
                3. 「打开权限页」→ ui_navigate("/governance/permissions")
                4. 「平台怎么用 / 最佳实践」→ console_knowledge_search，并引用 [docId#chunkId]
                5. 「最近用量」→ usage_summary，必要时 ui_navigate("/ops/billing")
                6. 「打开知识库」→ ui_navigate("/workbench/knowledge")

                ## 边界
                - 不执行任意 shell、不改生产配置（配置变更请引导用户到对应管理页手动确认）
                - 不确定时说明并询问；给出路径而不是编造数字
                """;
        if (catalog.findAgent(tenantId, botId).isEmpty()) {
            catalog.upsertAgent(new AgentRecord(
                    tenantId, botId, "控制台智能机器人", AgentStatus.ACTIVE, Instant.now()));
            log.info("seed console-bot tenant={}", tenantId);
        }
        if (catalog.findVersion(tenantId, botId, 1).isEmpty()) {
            catalog.upsertVersion(new AgentVersionRecord(
                    tenantId, botId, 1, VersionStatus.PUBLISHED,
                    properties.modelId(), prompt, null,
                    Instant.now(), Instant.now()));
            log.info("seed console-bot version tenant={} v1", tenantId);
        }
        if (catalog.findRelease(tenantId, botId).isEmpty()) {
            catalog.upsertRelease(tenantId, botId, 1);
            log.info("seed console-bot release tenant={} -> v1", tenantId);
        }
        governance.setMode(tenantId, botId, "DEFAULT");
        // 工具登记进治理目录（管理台「MCP 与工具」/ Agent 详情可见）——平台最佳实践可被管理
        seedConsoleBotTools(tenantId);
        // 机器人工具均为只读/导航，默认 ALLOW；下单仍 ASK
        for (String tool : List.of(
                "platform_status", "list_agents", "agent_detail",
                "list_permissions", "list_business_tools",
                "list_skills", "list_knowledge", "console_knowledge_search",
                "list_workflows", "list_teams",
                "usage_summary", "list_audit",
                "ui_navigate", "ui_focus", "ui_notify", "ui_ask_user", "ui_suggest",
                "safe_lookup", "knowledge_search")) {
            seedRuleIfAbsent(tenantId, botId, tool, "ALLOW");
        }
        seedRuleIfAbsent(tenantId, botId, "dangerous_write", "ASK");
        seedConsoleBotKnowledge(tenantId);
        seedConsoleBotSkill(tenantId);
    }

    /** 将 console-bot 工具登记到 business_tool（IN_PROCESS 目录可见）。 */
    private void seedConsoleBotTools(String tenantId) {
        String owner = "console-bot";
        // 平台查询
        upsertBotTool(tenantId, owner, "platform_status", "查询 AI 中台运行状态（就绪/限流/运行时配置）。只读。");
        upsertBotTool(tenantId, owner, "list_agents", "列出当前租户 Agent 与发布版本。只读。");
        upsertBotTool(tenantId, owner, "agent_detail", "查看单个 Agent 详情与版本列表。只读。");
        upsertBotTool(tenantId, owner, "list_permissions", "列出 Agent 权限规则与模式。只读。");
        upsertBotTool(tenantId, owner, "list_business_tools", "列出业务工具与 MCP 服务器。只读。");
        upsertBotTool(tenantId, owner, "list_skills", "列出技能市场技能。只读。");
        upsertBotTool(tenantId, owner, "list_knowledge", "列出知识库文档。只读。");
        upsertBotTool(tenantId, owner, "console_knowledge_search", "知识库检索（带引用片段）。只读。");
        upsertBotTool(tenantId, owner, "list_workflows", "列出 Workflow 与最近运行。只读。");
        upsertBotTool(tenantId, owner, "list_teams", "列出 Team 协作团队。只读。");
        upsertBotTool(tenantId, owner, "usage_summary", "查询配额与用量汇总。只读。");
        upsertBotTool(tenantId, owner, "list_audit", "查看最近审计日志。只读。");
        // UI 深度交互
        upsertBotTool(tenantId, owner, "ui_navigate", "指挥控制台 UI 跳转页面（返回 ui.actions）。只读。");
        upsertBotTool(tenantId, owner, "ui_focus", "在 UI 中聚焦/打开实体详情。只读。");
        upsertBotTool(tenantId, owner, "ui_notify", "弹出 Toast 通知。只读。");
        upsertBotTool(tenantId, owner, "ui_ask_user", "在机器人面板追问（可带快捷选项）。只读。");
        upsertBotTool(tenantId, owner, "ui_suggest", "生成可点击快捷操作芯片。只读。");
    }

    private void upsertBotTool(String tenantId, String owner, String name, String desc) {
        boolean exists = governance.listTools(tenantId).stream().anyMatch(t -> t.toolName().equals(name));
        if (exists) {
            return;
        }
        String config = "{\"owner\":\"" + owner + "\",\"builtin\":true,\"surface\":\"console-bot\"}";
        governance.registerTool(tenantId, name, desc, BusinessToolRecord.KIND_IN_PROCESS, config);
        log.info("seed console-bot business tool {} tenant={}", name, tenantId);
    }

    /** 控制台机器人知识库（平台最佳实践），按标题幂等补种。 */
    private void seedConsoleBotKnowledge(String tenantId) {
        if (knowledge == null) {
            return;
        }
        try {
            boolean hasGuide = knowledge.listDocuments(tenantId).stream()
                    .anyMatch(d -> d.title() != null && d.title().contains("控制台智能机器人"));
            if (!hasGuide) {
                knowledge.ingest(tenantId, "控制台智能机器人使用指南", "console-bot",
                        """
                        # 控制台智能机器人（小助）使用指南

                        ## 是什么
                        右下角浮动机器人，agentId=console-bot，是 AI 中台的**平台最佳实践智能体**：
                        目录可管理、工具可治理、知识可检索、权限可审计。

                        ## 能做什么
                        1. 查询平台：Agent / 权限 / 工具 / 技能 / 知识 / 编排 / 用量 / 审计
                        2. 驱动 UI：跳转页面、打开详情、Toast、追问、快捷芯片
                        3. 通用工具：safe_lookup（只读）、dangerous_write（危险写，需 HITL）

                        ## 如何管理
                        - Agent：控制台「Agent 列表」→ console-bot（可改系统提示词、版本、发布）
                        - 工具：「MCP 与工具」→ 业务工具，owner=console-bot 的内置工具
                        - 权限：「权限与模式」→ 选 agentId=console-bot
                        - 知识：「知识库」检索「小助 / console-bot / 最佳实践」

                        ## UI 交互约定
                        工具返回 JSON 中的 ui.actions 会被前端自动执行：
                        navigate / focus / toast / askUser / chips。
                        页面可用 botAsk() 或标题「问小助」注入上下文。

                        ## 最佳实践
                        1. 平台问题优先调工具，不编造数字
                        2. 危险写操作走 HITL，不跳过确认
                        3. 回答后给出可点击下一步
                        4. 知识问题必须引用 [docId#chunkId]
                        """);
                log.info("seed console-bot knowledge guide tenant={}", tenantId);
            }
            boolean hasMap = knowledge.listDocuments(tenantId).stream()
                    .anyMatch(d -> d.title() != null && d.title().contains("AI中台能力地图"));
            if (!hasMap) {
                knowledge.ingest(tenantId, "AI中台能力地图", "console-bot",
                        """
                        # AI 中台能力地图

                        ## 工作台
                        - 智能对话：业务 Agent 多轮对话 / SSE / HITL 审批
                        - 审批中心：工具调用人工确认
                        - 知识库：文档上传、检索、引用

                        ## Agent 中心
                        - Agent 列表 / 版本 / 发布切流
                        - 技能市场：上传、审核、可见性
                        - 编排 Workflow：ECHO / AGENT / HUMAN_GATE
                        - Team 协作：多 Agent 组队

                        ## 治理中心
                        - 权限与模式：ALLOW / ASK / DENY
                        - MCP 与工具：业务工具登记（IN_PROCESS / HTTP）、MCP 服务器
                        - 渠道 Channel / External Agent / IAM-RBAC

                        ## 运营中心
                        - 运行概览、会话日志、配额用量、审计、沙箱 FS、模型凭证

                        ## 系统
                        - 环境就绪（Redis/PG）、配置热更、OpenAI 兼容调试

                        ## 智能机器人小助
                        内嵌 console-bot，覆盖上述能力的查询与 UI 导航，是平台最佳实践入口。
                        """);
                log.info("seed console-bot knowledge map tenant={}", tenantId);
            }
        } catch (RuntimeException e) {
            log.warn("seed console-bot knowledge skipped tenant={}: {}", tenantId, e.toString());
        }
    }

    /** 平台最佳实践技能（草稿，审核后进入技能市场）。 */
    private void seedConsoleBotSkill(String tenantId) {
        if (skillCatalog == null) {
            return;
        }
        boolean exists = skillCatalog.list(tenantId).stream()
                .anyMatch(s -> "console-bot-best-practice".equals(s.name()));
        if (exists) {
            return;
        }
        skillAdmin.upload(tenantId, "console-bot-best-practice", "1.0.0",
                "控制台智能机器人最佳实践：如何用小助管理平台并驱动 UI（平台参考实现）",
                """
                # console-bot-best-practice

                ## 何时使用
                需要了解如何把「对话式运维 / 控制台副驾」做成可治理的平台智能体时。

                ## 标准形态
                1. **目录化**：作为 Agent 播种（agentId=console-bot），可版本、可发布
                2. **工具治理**：工具登记 business_tool（owner=console-bot），权限 ALLOW/ASK
                3. **知识增强**：使用指南 + 能力地图入库，回答带引用
                4. **UI 联动**：ui_* 工具返回 ui.actions，前端执行导航/Toast/聚焦
                5. **HITL**：危险写操作保持 ASK

                ## 实现要点
                - 工具结果统一 {ok, data, ui:{actions:[]}}
                - 页面经 botBus 注入上下文
                - 系统提示词写清能力地图与 UI 协议
                """,
                "console-bot");
        log.info("seed console-bot skill draft tenant={}", tenantId);
    }

    private void seedGovernance(String tenantId, String agentId) {
        governance.setMode(tenantId, agentId, "DEFAULT");

        upsertTool(tenantId, "safe_lookup", "通用只读查询示例", "IN_PROCESS");
        upsertTool(tenantId, "dangerous_write", "危险写操作示例（需 HITL 确认）", "IN_PROCESS");

        seedRuleIfAbsent(tenantId, agentId, "dangerous_write", "ASK");
        seedRuleIfAbsent(tenantId, agentId, "safe_lookup", "ALLOW");
        seedRuleIfAbsent(tenantId, agentId, "knowledge_search", "ALLOW");
        // 不再播种 memory_search/session_search/memory_get/read_file 的 ALLOW
        // 工具面默认 disableMemoryTools + disableFilesystemTools
        // （工具清单已证实未注册）。若未来开启对应工具，需在此重新播种。
        purgeLegacyOrderDemo(tenantId);
    }

    /** 清理历史订单演示残留（工具登记 / 权限规则 / 知识标题）。 */
    private void purgeLegacyOrderDemo(String tenantId) {
        for (String tool : new String[] {"query_orders", "place_order"}) {
            try {
                if (governance.listTools(tenantId).stream().anyMatch(t -> tool.equals(t.toolName()))) {
                    governance.deleteTool(tenantId, tool);
                    log.info("purge legacy order tool {} tenant={}", tool, tenantId);
                }
            } catch (RuntimeException e) {
                log.warn("purge tool {} failed: {}", tool, e.toString());
            }
            for (String agent : new String[] {agentIdSafe(), "console-bot", "demo"}) {
                try {
                    governance.deleteRule(tenantId, agent, tool, "ALLOW", "*");
                    governance.deleteRule(tenantId, agent, tool, "ASK", "*");
                    governance.deleteRule(tenantId, agent, tool, "DENY", "*");
                } catch (RuntimeException ignored) {
                    // rule may not exist
                }
            }
        }
    }

    private String agentIdSafe() {
        return properties.name() == null || properties.name().isBlank() ? "demo" : properties.name();
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

    /** amp.nacos.ai.sync-on-start=true 时启动拉取 MCP / AgentCard（失败不阻断应用）。 */
    private void maybeSyncNacosAi() {
        try {
            if (nacosAiProperties == null || !nacosAiProperties.enabled() || !nacosAiProperties.syncOnStart()) {
                return;
            }
            String tenantId = defaultTenantId == null || defaultTenantId.isBlank() ? "acme" : defaultTenantId;
            Object mcp = nacosAiSync.syncMcp(tenantId);
            Object agents = nacosAiSync.importAgents(tenantId);
            log.info("nacos ai sync-on-start done tenant={} mcp={} agents={}", tenantId, mcp, agents);
        } catch (RuntimeException e) {
            log.warn("nacos ai sync-on-start skipped: {}", e.toString());
        }
    }
}
