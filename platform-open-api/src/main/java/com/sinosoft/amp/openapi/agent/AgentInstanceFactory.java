package com.sinosoft.amp.openapi.agent;

import com.sinosoft.amp.openapi.governance.GovernanceService;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.extensions.redis.state.RedisAgentStateStore;
import io.agentscope.harness.agent.HarnessAgent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.JedisPooled;

import java.nio.file.Path;
import java.util.Objects;

/**
 * 鎸夌洰褰曠増鏈厤缃?+ S4 娌荤悊瑙勫垯鏋勫缓 HarnessAgent 瀹炰緥銆? */
public class AgentInstanceFactory implements AgentInstanceCreator {

    private static final Logger log = LoggerFactory.getLogger(AgentInstanceFactory.class);

    private final AgentProperties defaults;
    private final JedisPooled jedis;
    private final String stateKeyPrefix;
    private final GovernanceService governance;
    private final com.sinosoft.amp.openapi.skill.SkillCatalogRepository skillCatalog;
    private final com.sinosoft.amp.openapi.knowledge.KnowledgeService knowledge;
    private final com.sinosoft.amp.openapi.governance.McpConnectService mcpConnect;
    private final com.sinosoft.amp.openapi.sandbox.DockerSandboxService sandbox;
    private final io.agentscope.harness.agent.filesystem.remote.store.BaseStore baseStore;
    private final String fsMode;
    private final String fsScope;
    private final ToolPolicyProperties toolPolicy;
    private final com.sinosoft.amp.openapi.model.ModelCredentialService modelCredentials;
    private final com.sinosoft.amp.openapi.skill.SkillCanaryProperties skillCanary;
    /** 骞冲彴鍏变韩 MessageBus锛堝敜閱掑垎鍙戣闃呭悓涓€瀹炰緥锛夛紱null 鏃?harness build 鍐呴儴鎸?fs 鑷姩娲剧敓銆?*/
    private io.agentscope.harness.agent.bus.MessageBus messageBus;

    public void setMessageBus(io.agentscope.harness.agent.bus.MessageBus messageBus) {
        this.messageBus = messageBus;
    }

    public AgentInstanceFactory(AgentProperties defaults) {
        this(defaults, null, null, null, null, null, null, "local", "USER", null, null);
    }

    public AgentInstanceFactory(AgentProperties defaults, GovernanceService governance) {
        this(defaults, governance, null, null, null, null, null, "local", "USER", null, null);
    }

    public AgentInstanceFactory(
            AgentProperties defaults,
            GovernanceService governance,
            com.sinosoft.amp.openapi.skill.SkillCatalogRepository skillCatalog) {
        this(defaults, governance, skillCatalog, null, null, null, null, "local", "USER", null, null);
    }

    public AgentInstanceFactory(
            AgentProperties defaults,
            GovernanceService governance,
            com.sinosoft.amp.openapi.skill.SkillCatalogRepository skillCatalog,
            com.sinosoft.amp.openapi.knowledge.KnowledgeService knowledge) {
        this(defaults, governance, skillCatalog, knowledge, null, null, null, "local", "USER", null, null);
    }

    public AgentInstanceFactory(
            AgentProperties defaults,
            GovernanceService governance,
            com.sinosoft.amp.openapi.skill.SkillCatalogRepository skillCatalog,
            com.sinosoft.amp.openapi.knowledge.KnowledgeService knowledge,
            com.sinosoft.amp.openapi.governance.McpConnectService mcpConnect) {
        this(defaults, governance, skillCatalog, knowledge, mcpConnect, null, null, "local", "USER", null, null);
    }

    public AgentInstanceFactory(
            AgentProperties defaults,
            GovernanceService governance,
            com.sinosoft.amp.openapi.skill.SkillCatalogRepository skillCatalog,
            com.sinosoft.amp.openapi.knowledge.KnowledgeService knowledge,
            com.sinosoft.amp.openapi.governance.McpConnectService mcpConnect,
            com.sinosoft.amp.openapi.sandbox.DockerSandboxService sandbox) {
        this(defaults, governance, skillCatalog, knowledge, mcpConnect, sandbox, null, "local", "USER", null, null);
    }

    public AgentInstanceFactory(
            AgentProperties defaults,
            GovernanceService governance,
            com.sinosoft.amp.openapi.skill.SkillCatalogRepository skillCatalog,
            com.sinosoft.amp.openapi.knowledge.KnowledgeService knowledge,
            com.sinosoft.amp.openapi.governance.McpConnectService mcpConnect,
            com.sinosoft.amp.openapi.sandbox.DockerSandboxService sandbox,
            io.agentscope.harness.agent.filesystem.remote.store.BaseStore baseStore,
            String fsMode,
            String fsScope) {
        this(defaults, governance, skillCatalog, knowledge, mcpConnect, sandbox, baseStore, fsMode, fsScope, null, null);
    }

    public AgentInstanceFactory(
            AgentProperties defaults,
            GovernanceService governance,
            com.sinosoft.amp.openapi.skill.SkillCatalogRepository skillCatalog,
            com.sinosoft.amp.openapi.knowledge.KnowledgeService knowledge,
            com.sinosoft.amp.openapi.governance.McpConnectService mcpConnect,
            com.sinosoft.amp.openapi.sandbox.DockerSandboxService sandbox,
            io.agentscope.harness.agent.filesystem.remote.store.BaseStore baseStore,
            String fsMode,
            String fsScope,
            ToolPolicyProperties toolPolicy,
            com.sinosoft.amp.openapi.model.ModelCredentialService modelCredentials) {
        this(defaults, governance, skillCatalog, knowledge, mcpConnect, sandbox, baseStore, fsMode, fsScope,
                toolPolicy, modelCredentials, null);
    }

    public AgentInstanceFactory(
            AgentProperties defaults,
            GovernanceService governance,
            com.sinosoft.amp.openapi.skill.SkillCatalogRepository skillCatalog,
            com.sinosoft.amp.openapi.knowledge.KnowledgeService knowledge,
            com.sinosoft.amp.openapi.governance.McpConnectService mcpConnect,
            com.sinosoft.amp.openapi.sandbox.DockerSandboxService sandbox,
            io.agentscope.harness.agent.filesystem.remote.store.BaseStore baseStore,
            String fsMode,
            String fsScope,
            ToolPolicyProperties toolPolicy,
            com.sinosoft.amp.openapi.model.ModelCredentialService modelCredentials,
            com.sinosoft.amp.openapi.skill.SkillCanaryProperties skillCanary) {
        this.defaults = Objects.requireNonNull(defaults, "defaults");
        this.jedis = new JedisPooled(defaults.redisUri());
        this.stateKeyPrefix = defaults.stateKeyPrefix() == null || defaults.stateKeyPrefix().isBlank()
                ? "amp:session:"
                : defaults.stateKeyPrefix();
        this.governance = governance;
        this.skillCatalog = skillCatalog;
        this.knowledge = knowledge;
        this.mcpConnect = mcpConnect;
        this.sandbox = sandbox;
        this.baseStore = baseStore;
        this.fsMode = fsMode == null ? "local" : fsMode;
        this.fsScope = fsScope == null ? "USER" : fsScope;
        this.toolPolicy = toolPolicy == null ? defaultToolPolicy() : toolPolicy;
        this.modelCredentials = modelCredentials;
        this.skillCanary = skillCanary == null
                ? new com.sinosoft.amp.openapi.skill.SkillCanaryProperties(null, 100, null, null)
                : skillCanary;
    }

    private static ToolPolicyProperties defaultToolPolicy() {
        return new ToolPolicyProperties(ToolPolicyProperties.MODE_DENY, null, null, false, false, false);
    }

    private String fsMode() {
        return fsMode;
    }

    private String fsScope() {
        return fsScope;
    }

    @Override
    public HarnessAgent create(String requestTenantId, String agentId, int version, String modelId, String sysPrompt, String workspace) {
        // tenant from request key; fallback env for non-request context
        String tenant = requestTenantId == null || requestTenantId.isBlank()
                ? resolveTenant()
                : requestTenantId;
        String effectiveModel = blankTo(modelId, defaults.modelId());
        String effectivePrompt = blankTo(sysPrompt, defaults.sysPrompt());
        // 鏃х瀛?瀛橀噺琛屾妸 workspace 鍐欐垚瑁稿熀纭€璺緞锛岃鍚屾湭璁剧疆锛岀粺涓€璧?{base}/{tenant}/v{version}
        boolean workspaceUnset = workspace == null || workspace.isBlank()
                || workspace.equals(defaults.workspace());
        String effectiveWorkspace = workspaceUnset
                ? Path.of(defaults.workspace(), tenant, "v" + version).toString()
                : workspace;
        AgentStateStore stateStore = RedisAgentStateStore.builder()
                .jedisClient(jedis)
                .keyPrefix(stateKeyPrefix)
                .build();

        HarnessAgent.Builder builder = HarnessAgent.builder()
                .name(agentId)
                .sysPrompt(effectivePrompt)
                .model(effectiveModel)
                .workspace(Path.of(effectiveWorkspace))
                .stateStore(stateStore)
                .stopOnReject(true)
                .enablePendingToolRecovery(true)
                .disableMemoryTools()
                // S7 OTel锛欰gent / Model / Tool 閾捐矾
                .middleware(new io.agentscope.core.tracing.OtelTracingMiddleware())
                // 瀹樻柟閰嶆柟锛欻ITL 鍏ㄦ嫆鍚庣珛鍗冲仠姝㈡湰杞紙GenerateReason.ALL_TOOLS_DENIED锛夛紝閬垮厤妯″瀷绌鸿浆
                .middleware(new StopOnAllDeniedMiddleware());

        // AgentScope 杩愯鏃跺弬鏁帮紙AgentScope 2.0锛氭ā鍨嬮噸璇?鍥為€€銆佷笂涓嬫枃鍘嬬缉銆亀orkspace 涓婁笅鏂囬绠楋級
        if (defaults.maxRetries() != null) {
            builder.maxRetries(defaults.maxRetries());
        }
        if (defaults.maxContextTokens() != null) {
            builder.maxContextTokens(defaults.maxContextTokens());
        }
        if (defaults.fallbackModelId() != null && !defaults.fallbackModelId().isBlank()) {
            builder.fallbackModel(defaults.fallbackModelId().trim());
            log.info("fallback model enabled agent={} fallback={}", agentId, defaults.fallbackModelId().trim());
        }
        // 瀵硅瘽鍘嬬缉锛氭寜 Java 瀹樻柟鎺ㄨ崘鏄惧紡閰嶇疆锛?.0.3 榛樿寮€锛屼絾鏄惧紡鍖栭槻鐗堟湰鍗囩骇婕傜Щ锛夛紱
        int triggerMsgs = defaults.compactionTriggerMessages() == null
                ? 30 : defaults.compactionTriggerMessages();
        int keepMsgs = defaults.compactionKeepMessages() == null
                ? 10 : defaults.compactionKeepMessages();
        var compactionBuilder = io.agentscope.harness.agent.memory.compaction.CompactionConfig.builder()
                .triggerMessages(triggerMsgs)
                .keepMessages(keepMsgs);
        int triggerTokens = defaults.compactionTriggerTokens() == null ? 0 : defaults.compactionTriggerTokens();
        if (triggerTokens > 0) {
            compactionBuilder.triggerTokens(triggerTokens);
        }
        builder.compaction(compactionBuilder.build());
        if (defaults.toolResultEviction()) {
            builder.toolResultEviction(
                    io.agentscope.harness.agent.memory.compaction.ToolResultEvictionConfig.defaults());
        } else {
            builder.disableToolResultEviction();
        }
        log.info("compaction config agent={} triggerMessages={} keepMessages={} triggerTokens={} toolResultEviction={}",
                agentId, triggerMsgs, keepMsgs, triggerTokens, defaults.toolResultEviction());
        builder.enableMetaTool(defaults.enableMetaTool());
        if (defaults.enablePlanMode() || defaults.enableTaskList() || defaults.enableMetaTool()) {
            log.info("agentscope 2.0 features agent={} planMode={} taskList={} metaTool={}",
                    agentId, defaults.enablePlanMode(), defaults.enableTaskList(), defaults.enableMetaTool());
        }
        // 寮傛宸ュ叿鍗歌浇锛?0 鐢熸晥锛夛細AsyncToolMiddleware 瓒呮椂杞悗鍙?+ 瀹屾垚鍞ら啋
        if (defaults.asyncToolTimeoutMs() != null && defaults.asyncToolTimeoutMs() > 0) {
            builder.asyncToolTimeout(java.time.Duration.ofMillis(defaults.asyncToolTimeoutMs()));
            log.info("async tool offload enabled agent={} timeoutMs={}", agentId, defaults.asyncToolTimeoutMs());
        }
        if (messageBus != null) {
            builder.messageBus(messageBus);
        }

        if (!toolPolicy.enableFilesystem()) {
            builder.disableFilesystemTools();
        }
        if (!toolPolicy.enableAgentSpawn()) {
            builder.disableSubagents();
        }

        // 妯″瀷鍑瘉锛氬簱鍐?apiKey/baseUrl 浼樺厛娉ㄥ叆 ModelCreationContext
        if (modelCredentials != null) {
            builder.modelResolver(mid -> modelCredentials.resolveModel(tenant, mid));
        }

        // 涓氬姟 Agent 婕旂ず锛氭敹绱у伐鍏烽潰锛屼繚鐣?knowledge_search / 璁㈠崟宸ュ叿
        io.agentscope.harness.agent.tools.ToolsConfig toolsConfig = new ToolPolicyApplier(toolPolicy).apply();
        builder.toolsConfig(toolsConfig);

        if (governance != null && tenant != null) {
            try {
                builder.permissionContext(governance.buildPermissionContext(tenant, agentId));
            } catch (RuntimeException e) {
                log.warn("apply permission context for agent {} failed: {}", agentId, e.toString());
            }
            try {
                java.util.Map<String, io.agentscope.harness.agent.tools.McpServerConfig> mcp =
                        mcpConnect == null ? java.util.Map.of()
                                : mcpConnect.buildToolsConfigMap(tenant);
                if (!mcp.isEmpty()) {
                    io.agentscope.harness.agent.tools.ToolsConfig withMcp = new ToolPolicyApplier(toolPolicy).apply();
                    withMcp.setMcpServers(new java.util.LinkedHashMap<>(mcp));
                    builder.toolsConfig(withMcp);
                    log.info("mcp tools config injected agent={} servers={}", agentId, mcp.keySet());
                }
            } catch (RuntimeException e) {
                log.warn("inject mcp config failed agent={}: {}", agentId, e.toString());
            }
        }
        // S5: skill marketplace APPROVED only + canary
        if (skillCatalog != null && tenant != null) {
            builder.skillRepository(
                    new com.sinosoft.amp.openapi.skill.MarketplaceSkillRepository(skillCatalog, tenant));
            builder.enableSkillPromotionGate(
                    new io.agentscope.harness.agent.skill.curator.RejectAllGate(),
                    new com.sinosoft.amp.openapi.skill.SkillCanaryFilter(skillCanary));
            log.info("skill canary filter enabled agent={} env={}", agentId,
                    skillCanary == null ? "-" : skillCanary.environment());
        }

        // T9: Remote FS (PG BaseStore) + Redis State -> DistributedStore
        if (baseStore != null && "remote".equalsIgnoreCase(fsMode())) {
            io.agentscope.harness.agent.IsolationScope scope =
                    "SESSION".equalsIgnoreCase(fsScope())
                            ? io.agentscope.harness.agent.IsolationScope.SESSION
                            : io.agentscope.harness.agent.IsolationScope.USER;
            io.agentscope.harness.agent.filesystem.spec.RemoteFilesystemSpec remoteFs =
                    new io.agentscope.harness.agent.filesystem.spec.RemoteFilesystemSpec(baseStore)
                            .isolationScope(scope)
                            .addSharedPrefix("agents/" + agentId);
            builder.filesystem(remoteFs);
            builder.distributedStore(io.agentscope.harness.agent.DistributedStore.builder()
                    .agentStateStore(stateStore)
                    .baseStore(baseStore)
                    .build());
            log.info("remote filesystem enabled agent={} scope={} baseStore=PgBaseStore", agentId, scope);
        }

        HarnessAgent agent = builder.build();
        // @Tool registry -> ReflectiveFunctionTool; PermissionEngine may hit ASK
        boolean allowOrders = toolPolicy.allowlistMode()
                ? toolPolicy.effectiveAllow().contains("query_orders") || toolPolicy.effectiveAllow().contains("place_order")
                : true;
        if (allowOrders) {
            agent.getToolkit().registerTool(new com.sinosoft.amp.openapi.tool.OrderToolset());
        }
        // S6锛氱煡璇嗘绱㈠伐鍏凤紙甯﹀紩鐢級
        boolean allowKnowledge = toolPolicy.allowlistMode()
                ? toolPolicy.effectiveAllow().contains("knowledge_search")
                : true;
        if (knowledge != null && tenant != null && allowKnowledge) {
            agent.getToolkit().registerTool(new com.sinosoft.amp.openapi.knowledge.KnowledgeToolset(knowledge, tenant));
        }
        // T7: Docker sandbox exec tool (default off)
        boolean allowSandbox = toolPolicy.allowlistMode()
                ? toolPolicy.effectiveAllow().contains("sandbox_exec")
                : true;
        if (sandbox != null && sandbox.enabled() && tenant != null && allowSandbox) {
            agent.getToolkit().registerTool(new com.sinosoft.amp.openapi.tool.SandboxToolset(sandbox, tenant));
            log.info("sandbox_exec tool registered agent={} scope={}", agentId,
                    sandbox.status().get("isolationScope"));
        }
        log.info("build agent instance agentId={} version={} modelId={} toolMode={} tenant={} workspace={}",
                agentId, version, effectiveModel, toolPolicy.mode(), tenant, effectiveWorkspace);
        log.info("agent toolkit tools agentId={} count={} names={}",
                agentId, agent.getToolkit().getToolNames().size(),
                String.join(",", agent.getToolkit().getToolNames()));
        return agent;
    }

    public void close() {
        try {
            jedis.close();
        } catch (RuntimeException e) {
            log.warn("close jedis failed", e);
        }
    }

    private static String blankTo(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private String resolveTenant() {
        return System.getProperty("amp.default.tenant",
                System.getenv().getOrDefault("AMP_TENANT_ID", "acme"));
    }
}
