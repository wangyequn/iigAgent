package com.sinosoft.amp.openapi.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

import java.util.List;
import java.util.Map;

/**
 * amp.auth.keys[*]: key → 绑定租户 + scopes（open / admin）。
 *
 * <p>授权模型：**租户 + scope 为准**。`agents` 为可选收窄（白名单）：
 * 空 / {@code *} / 未配置 = 租户内全部 Agent；仅当显式列出 agentId 时才限制。
 * scopes 为空表示兼容旧行为（不限制）；生产应显式声明。
 */
@ConfigurationProperties(prefix = "amp.auth")
public record AuthProperties(
        boolean enabled,
        List<KeyEntry> keys
) {

    public static final String SCOPE_OPEN = "open";
    public static final String SCOPE_ADMIN = "admin";
    public static final String AGENT_WILDCARD = "*";

    public record KeyEntry(
            String key,
            String tenantId,
            List<String> agents,
            List<String> scopes
    ) {
        // 标注规范构造函数：KeyEntry 存在便捷重载，不标注会被当作 JavaBean 绑定而 "left unbound"
        @ConstructorBinding
        public KeyEntry(String key, String tenantId, List<String> agents, List<String> scopes) {
            this.key = key;
            this.tenantId = tenantId;
            this.agents = agents;
            this.scopes = scopes;
        }

        public KeyEntry(String key, String tenantId, List<String> agents) {
            this(key, tenantId, agents, null);
        }

        /** 空 / * / 未配置 = 租户内全部 Agent；显式列表才是白名单。 */
        public boolean allows(String agentId) {
            if (agents == null || agents.isEmpty()) {
                return true;
            }
            if (agents.contains(AGENT_WILDCARD)) {
                return true;
            }
            return agentId != null && agents.contains(agentId);
        }

        /** scopes 未配置时不限制（兼容旧配置/单测）；否则需包含 scope。admin 不隐含 open。 */
        public boolean allowsScope(String scope) {
            if (scopes == null || scopes.isEmpty()) {
                return true;
            }
            return scopes.contains(scope);
        }
    }

    public Map<String, KeyEntry> byKey() {
        Map<String, KeyEntry> map = new java.util.LinkedHashMap<>();
        if (keys != null) {
            for (KeyEntry entry : keys) {
                if (entry.key() != null && !entry.key().isBlank()) {
                    map.put(entry.key(), entry);
                }
            }
        }
        return map;
    }
}
