package com.example.agent.runtime;

import com.example.agent.config.AgentProperties;
import com.example.agent.tool.SampleTools;
import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionMode;
import io.agentscope.core.permission.PermissionRule;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.extensions.redis.state.RedisAgentStateStore;
import io.agentscope.harness.agent.HarnessAgent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.JedisPooled;

import java.nio.file.Path;

/**
 * Harness 装配金路径（Copy 自中台 AgentInstanceFactory，刻意删掉治理/技能/知识/MCP）。
 *
 * <p>对齐要点（官方 + 实战坑）：
 * <ol>
 *   <li>State 必须用 Redis（多副本会话不丢）；本地 JsonFile 仅限开发。</li>
 *   <li>compaction 显式配置（triggerMessages/keepMessages），防 2.0.x 版本漂移。</li>
 *   <li>stopOnReject(true) + StopOnAllDeniedMiddleware，拒绝后不空转。</li>
 *   <li>权限 ruleContent 存 "*" 时必须映射为 null，否则规则永不命中（见 {@link #normalizeRuleContent}）。</li>
 * </ol>
 */
public class HarnessFactory {

    private static final Logger log = LoggerFactory.getLogger(HarnessFactory.class);

    private final AgentProperties props;
    private final JedisPooled jedis;

    public HarnessFactory(AgentProperties props) {
        this.props = props;
        this.jedis = new JedisPooled(props.redisUri() == null
                ? "redis://127.0.0.1:6379"
                : props.redisUri());
    }

    /**
     * 构建单例 Agent（进程内按 name 复用由 {@link AgentSessions} 负责）。
     *
     * @param agentId 逻辑名，如 demo
     * @param sysPrompt 系统提示词；空则用配置默认
     */
    public HarnessAgent build(String agentId, String sysPrompt) {
        String prompt = (sysPrompt == null || sysPrompt.isBlank()) ? props.sysPrompt() : sysPrompt;
        if (prompt == null || prompt.isBlank()) {
            prompt = "你是业务助手，用中文简洁回答。";
        }
        Path workspace = Path.of(props.effectiveWorkspace(), agentId);
        AgentStateStore stateStore = RedisAgentStateStore.builder()
                .jedisClient(jedis)
                .keyPrefix(props.effectiveStateKeyPrefix())
                .build();

        HarnessAgent.Builder builder = HarnessAgent.builder()
                .name(agentId)
                .sysPrompt(prompt)
                .model(props.effectiveModelId())
                .workspace(workspace)
                .stateStore(stateStore)
                .stopOnReject(true)
                .enablePendingToolRecovery(true)
                .disableMemoryTools()
                .middleware(new StopOnAllDeniedMiddleware());

        // 对话压缩：Java 官方推荐显式配置
        var compaction = io.agentscope.harness.agent.memory.compaction.CompactionConfig.builder()
                .triggerMessages(props.triggerMessages())
                .keepMessages(props.keepMessages())
                .build();
        builder.compaction(compaction);
        builder.toolResultEviction(
                io.agentscope.harness.agent.memory.compaction.ToolResultEvictionConfig.defaults());

        // 演示权限：dangerous_write → ASK（HITL）；safe_lookup → ALLOW
        builder.permissionContext(demoPermissionContext());

        HarnessAgent agent = builder.build();
        agent.getToolkit().registerTool(new SampleTools());
        log.info("harness built agent={} model={} workspace={}", agentId, props.effectiveModelId(), workspace);
        return agent;
    }

    /**
     * 演示 PermissionContext：写操作 ASK、只读 ALLOW。
     *
     * <p><b>坑：</b>ToolBase.matchRule 对非文件类工具仅当 ruleContent==null 才匹配；
     * 通配符 "*" <b>不是</b>通配，必须 normalize 成 null。
     */
    public static PermissionContextState demoPermissionContext() {
        PermissionContextState.Builder b = PermissionContextState.builder()
                .mode(PermissionMode.DEFAULT);
        b.addAllowRule("safe_lookup", new PermissionRule(
                "safe_lookup", normalizeRuleContent("*"), PermissionBehavior.ALLOW, "starter"));
        b.addAskRule("dangerous_write", new PermissionRule(
                "dangerous_write", normalizeRuleContent("*"), PermissionBehavior.ASK, "starter"));
        return b.build();
    }

    /** 与中台 GovernanceService.normalizeRuleContent 同一语义。 */
    public static String normalizeRuleContent(String stored) {
        if (stored == null || stored.isBlank() || "*".equals(stored.trim())) {
            return null;
        }
        return stored;
    }
}
