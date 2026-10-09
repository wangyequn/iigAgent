package com.sinosoft.amp.openapi.security;

import java.util.List;

/**
 * 请求身份上下文。
 *
 * <p>优先级：JWT（SecurityContext）&gt; API Key（线程本地）。
 * 管理面 scope 仅约束机器 Key；JWT 人身份走 RBAC（tenant_admin / 权限点）。
 */
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

    /** JWT 人轨身份；无则 null。 */
    public static JwtIdentity currentJwt() {
        return JwtAuthSupport.current();
    }

    public static String requireTenantId() {
        JwtIdentity jwt = JwtAuthSupport.current();
        if (jwt != null && jwt.tenantId() != null && !jwt.tenantId().isBlank()) {
            return jwt.tenantId();
        }
        AuthProperties.KeyEntry entry = HOLDER.get();
        if (entry == null || entry.tenantId() == null || entry.tenantId().isBlank()) {
            throw new UnauthorizedException("unauthenticated");
        }
        return entry.tenantId();
    }

    /**
     * Agent 访问：默认按**租户 + scope/RBAC** 授权（Key 绑定租户即可调本租户 Agent）。
     * 仅当 Key 显式配置了 agents 白名单（非空且不含 *）时才收窄；JWT 人身份不限制 agent 白名单。
     */
    public static void requireAgent(String agentId) {
        if (JwtAuthSupport.current() != null) {
            return;
        }
        AuthProperties.KeyEntry entry = HOLDER.get();
        if (entry == null) {
            throw new UnauthorizedException("unauthenticated");
        }
        if (!entry.allows(agentId)) {
            throw new UnauthorizedException("api key not allowed for agent: " + agentId);
        }
    }

    /**
     * 管理面二次校验。
     * API Key 需 admin scope（过滤器已按路径强制）；JWT 需 tenant_admin / admin 角色。
     */
    public static void requireAdmin() {
        JwtIdentity jwt = JwtAuthSupport.current();
        if (jwt != null) {
            if (hasAdminRole(jwt)) {
                return;
            }
            throw new UnauthorizedException("api key missing scope: admin");
        }
        AuthProperties.KeyEntry entry = HOLDER.get();
        if (entry == null) {
            throw new UnauthorizedException("unauthenticated");
        }
        if (!entry.allowsScope(AuthProperties.SCOPE_ADMIN)) {
            throw new UnauthorizedException("api key missing scope: admin");
        }
    }

    private static boolean hasAdminRole(JwtIdentity jwt) {
        return jwt.roles().stream().anyMatch(r ->
                "tenant_admin".equalsIgnoreCase(r) || "admin".equalsIgnoreCase(r));
    }

    public static String actorLabel() {
        JwtIdentity jwt = JwtAuthSupport.current();
        if (jwt != null && jwt.userId() != null && !jwt.userId().isBlank()) {
            return jwt.userId();
        }
        AuthProperties.KeyEntry entry = HOLDER.get();
        if (entry == null) {
            return "anonymous";
        }
        String key = entry.key();
        String tail = key == null || key.length() <= 4 ? "****" : "****" + key.substring(key.length() - 4);
        return entry.tenantId() + ":" + tail;
    }

    /** 默认 demo 或请求指定且 Key 允许的 agentId。JWT 人身份默认 demo。 */
    public static String requireAgentIdOrDefault(String agentId) {
        if (JwtAuthSupport.current() != null) {
            return agentId == null || agentId.isBlank() ? "demo" : agentId;
        }
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
