package com.sinosoft.amp.openapi.agent;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * 工具面默认收紧：denylist（默认）或 allowlist；shell/filesystem/subagent 默认关。
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

    /** 探索/危险面默认关。 */
    public static final List<String> DEFAULT_DENY = List.of(
            "memory_search", "memory_get", "memory_save",
            "session_search", "session_list", "session_history",
            "list_files", "glob_files", "grep_files", "read_file",
            "write_file", "edit_file", "execute", "shell_execute",
            "web_search", "web_fetch", "agent_spawn", "load_skill_through_path");

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

    public List<String> effectiveDeny() {
        java.util.LinkedHashSet<String> deny = new java.util.LinkedHashSet<>(DEFAULT_DENY);
        if (enableAgentSpawn) {
            deny.remove("agent_spawn");
        }
        if (enableShell) {
            deny.remove("execute");
            deny.remove("shell_execute");
        }
        if (enableFilesystem) {
            deny.remove("list_files");
            deny.remove("glob_files");
            deny.remove("grep_files");
            deny.remove("read_file");
            deny.remove("write_file");
            deny.remove("edit_file");
        }
        if (extraDeny != null) {
            deny.addAll(extraDeny);
        }
        return List.copyOf(deny);
    }
}
