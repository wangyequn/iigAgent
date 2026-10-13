package com.sinosoft.amp.openapi.security;

import java.util.List;

/**
 * 请求身份上下文。
 *
 * <p>优先级：JWT（SecurityContext）&gt; Jeecg（X-Access-Token）&gt; API Key（线程本地）。
 * 管理面 scope 仅约束机器 Key；人身份走 RBAC。
 */
public final class AuthContext {

    private static final ThreadLocal<AuthProperties.KeyEntry> HOLDER = new ThreadLocal<>();
    private static final ThreadLocal<JeecgIdentity> JEECG = new ThreadLocal<>();
    /** 人轨原始票，供 HTTP/MCP 工具 authMode=user_token 透传业务侧。 */
    private static final InheritableThreadLocal<String> USER_TOKEN = new InheritableThreadLocal<>();

    private AuthContext() {
    }

    public static void set(AuthProperties.KeyEntry entry) {
        HOLDER.set(entry);
    }

    public static AuthProperties.KeyEntry get() {
        return HOLDER.get();
    }

    public static void setJeecg(JeecgIdentity identity) {
        JEECG.set(identity);
    }

    /** Jeecg（pc_platform）登录用户；无则 null。 */
    public static JeecgIdentity currentJeecg() {
        return JEECG.get();
    }

    public static void clearJeecg() {
        JEECG.remove();
        USER_TOKEN.remove();
    }

    public static void setUserToken(String token) {
        if (token == null || token.isBlank()) {
            USER_TOKEN.remove();
        } else {
            USER_TOKEN.set(token.trim());
        }
    }

    /** 当前用户票（X-Access-Token / Bearer 人轨 JWT）；无则 null。 */
    public static String currentUserToken() {
        return USER_TOKEN.get();
    }

    public static void clear() {
        HOLDER.remove();
        JEECG.remove();
        USER_TOKEN.remove();
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
        JeecgIdentity jeecg = JEECG.get();
        if (jeecg != null) {
            // 租户用平台配置的 tenantId；clientId 是应用标识不是租户
            return "acme";
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
        if (JwtAuthSupport.current() != null || JEECG.get() != null) {
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
        JeecgIdentity jeecg = JEECG.get();
        if (jeecg != null) {
            if (isJeecgAdmin(jeecg)) {
                return;
            }
            throw new UnauthorizedException("api key missing scope: admin");
        }
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

    /** pc_platform 不下发 admin:read 等平台权限码；用户/角色管理员按管理员旁路。 */
    static boolean isJeecgAdmin(JeecgIdentity jeecg) {
        return jeecg.hasAnyRole("admin", "tenant_admin", "superadmin")
                || jeecg.hasPerm("admin:write")
                || jeecg.hasPerm("admin:read")
                || jeecg.hasPerm("iam:admin")
                || jeecg.hasPerm("platform:user:setroles")
                || jeecg.hasPerm("platform:user:role")
                || jeecg.hasPerm("platform:user:add")
                || jeecg.hasPerm("system:role:add")
                || jeecg.hasPerm("system:user:add");
    }

    public static String actorLabel() {
        JwtIdentity jwt = JwtAuthSupport.current();
        if (jwt != null && jwt.userId() != null && !jwt.userId().isBlank()) {
            return jwt.userId();
        }
        JeecgIdentity jeecg = JEECG.get();
        if (jeecg != null && jeecg.username() != null && !jeecg.username().isBlank()) {
            return jeecg.username();
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
        if (JwtAuthSupport.current() != null || JEECG.get() != null) {
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
