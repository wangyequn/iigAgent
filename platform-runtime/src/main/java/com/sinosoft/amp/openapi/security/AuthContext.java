package com.sinosoft.amp.openapi.security;

import java.util.List;

public final class AuthContext {

    private static final ThreadLocal<AuthProperties.KeyEntry> HOLDER = new ThreadLocal<>();

    private AuthContext() {
    }

    public static void set(AuthProperties.KeyEntry entry) {
        HOLDER.set(entry);
    }

    public static AuthProperties.KeyEntry get() {
        return HOLDER.get();
    }

    public static void clear() {
        HOLDER.remove();
    }

    public static String requireTenantId() {
        AuthProperties.KeyEntry entry = HOLDER.get();
        if (entry == null || entry.tenantId() == null || entry.tenantId().isBlank()) {
            throw new UnauthorizedException("unauthenticated");
        }
        return entry.tenantId();
    }

    /**
     * Agent 访问：默认按**租户 + scope** 授权（Key 绑定租户即可调本租户 Agent）。
     * 仅当 Key 显式配置了 agents 白名单（非空且不含 *）时才收窄。
     */
    public static void requireAgent(String agentId) {
        AuthProperties.KeyEntry entry = HOLDER.get();
        if (entry == null) {
            throw new UnauthorizedException("unauthenticated");
        }
        if (!entry.allows(agentId)) {
            throw new UnauthorizedException("api key not allowed for agent: " + agentId);
        }
    }

    /** 管理面二次校验（过滤器已按路径强制 scope）。 */
    public static void requireAdmin() {
        AuthProperties.KeyEntry entry = HOLDER.get();
        if (entry == null) {
            throw new UnauthorizedException("unauthenticated");
        }
        if (!entry.allowsScope(AuthProperties.SCOPE_ADMIN)) {
            throw new UnauthorizedException("api key missing scope: admin");
        }
    }

    public static String actorLabel() {
        AuthProperties.KeyEntry entry = HOLDER.get();
        if (entry == null) {
            return "anonymous";
        }
        String key = entry.key();
        String tail = key == null || key.length() <= 4 ? "****" : "****" + key.substring(key.length() - 4);
        return entry.tenantId() + ":" + tail;
    }

    /** 默认 demo 或请求指定且 Key 允许的 agentId。 */
    public static String requireAgentIdOrDefault(String agentId) {
        AuthProperties.KeyEntry entry = HOLDER.get();
        if (entry == null) {
            throw new UnauthorizedException("unauthenticated");
        }
        if (agentId == null || agentId.isBlank()) {
            List<String> agents = entry.agents();
            if (agents != null && agents.size() == 1
                    && !agents.get(0).isBlank()
                    && !AuthProperties.AGENT_WILDCARD.equals(agents.get(0))) {
                return agents.get(0);
            }
            return "demo";
        }
        requireAgent(agentId);
        return agentId;
    }
}
