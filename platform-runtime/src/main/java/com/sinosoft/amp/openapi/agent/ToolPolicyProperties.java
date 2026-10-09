package com.sinosoft.amp.openapi.agent;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 工具面默认收紧：denylist（默认）或 allowlist；shell/filesystem/subagent 默认关。
 *
 * <p>危险面（shell/filesystem/memory/session/web/agent_spawn 等）在构建期由
 * disableXxx 摘除注册，工具根本不存在。deny 名单只对**真实注册**的工具才有意义——
 * 对未注册工具下发 deny 会触发 harness ToolFilter 的 "unknown tool" WARN，
 * 因此默认 deny 名单为空；需要封锁已注册工具时用 extra-deny（如 MCP 注入的工具）。
 */
@ConfigurationProperties(prefix = "amp.tools")
public record ToolPolicyProperties(
        /** deny = 黑名单（默认）；allow = 白名单（生产推荐） */
        String mode,
        List<String> allow,
        List<String> extraDeny,
        boolean enableAgentSpawn,
        boolean enableShell,
        boolean enableFilesystem
) {
    public static final String MODE_DENY = "deny";
    public static final String MODE_ALLOW = "allow";

    /** 最小工具面：知识检索 + 通用示例工具。 */
    public static final List<String> DEFAULT_ALLOW = List.of(
            "knowledge_search", "safe_lookup");

    public ToolPolicyProperties {
        if (mode == null || mode.isBlank()) {
            mode = MODE_DENY;
        }
    }

    public boolean allowlistMode() {
        return MODE_ALLOW.equalsIgnoreCase(mode);
    }

    public List<String> effectiveAllow() {
        return (allow == null || allow.isEmpty()) ? DEFAULT_ALLOW : allow;
    }

    /**
     * deny 名单 = 配置的 extra-deny（仅应列出真实注册的工具）。
     * shell/filesystem/subagent 的开关语义：false=构建期不注册（工具不可用，无需 deny）；
     * true=注册并放行（若要封锁用 extra-deny）。
     */
    public List<String> effectiveDeny() {
        Set<String> deny = new LinkedHashSet<>();
        if (extraDeny != null) {
            for (String tool : extraDeny) {
                if (tool != null && !tool.isBlank()) {
                    deny.add(tool.trim());
                }
            }
        }
        return List.copyOf(deny);
    }
}
