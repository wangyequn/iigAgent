package com.sinosoft.amp.openapi.iam;

import com.sinosoft.amp.openapi.security.UnauthorizedException;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Phase2 RBAC 基础：角色 → 权限点；请求头 X-User-Id / X-User-Roles 绑定人身份。
 * 机器身份（API Key）默认授予 agent_caller + 视 scope 授予 admin 全权（与一期兼容）。
 */
@Service
public class IamService {

    public static final String ROLE_TENANT_ADMIN = "tenant_admin";
    public static final String ROLE_OPS = "ops";
    public static final String ROLE_SKILL_REVIEWER = "skill_reviewer";
    public static final String ROLE_AUDITOR = "auditor";
    public static final String ROLE_DEVELOPER = "developer";
    public static final String ROLE_AGENT_CALLER = "agent_caller";

    private static final Map<String, Set<String>> DEFAULT_ROLE_PERMS = defaultRolePerms();

    /** tenantId -> userId -> roles */
    private final Map<String, Map<String, Set<String>>> userRoles = new ConcurrentHashMap<>();
    /** tenantId -> role -> extra perms */
    private final Map<String, Map<String, Set<String>>> rolePerms = new ConcurrentHashMap<>();

    private static Map<String, Set<String>> defaultRolePerms() {
        Map<String, Set<String>> m = new LinkedHashMap<>();
        m.put(ROLE_TENANT_ADMIN, Set.of(
                "orch:read", "orch:write", "orch:run",
                "iam:admin", "billing:read", "billing:write",
                "skill:review", "agent:call", "admin:read", "admin:write"));
        m.put(ROLE_OPS, Set.of(
                "orch:read", "orch:write", "orch:run",
                "admin:read", "admin:write", "agent:call"));
        m.put(ROLE_SKILL_REVIEWER, Set.of("skill:review", "agent:call", "admin:read"));
        m.put(ROLE_AUDITOR, Set.of("billing:read", "admin:read", "orch:read"));
        m.put(ROLE_DEVELOPER, Set.of(
                "orch:read", "orch:write", "orch:run",
                "agent:call", "admin:read", "admin:write"));
        m.put(ROLE_AGENT_CALLER, Set.of("agent:call"));
        return Collections.unmodifiableMap(m);
    }

    public void assignRole(String tenantId, String userId, String roleCode) {
        requireKnownRole(roleCode);
        userRoles.computeIfAbsent(tenantId, k -> new ConcurrentHashMap<>())
                .computeIfAbsent(userId, k -> new LinkedHashSet<>())
                .add(roleCode.toLowerCase(Locale.ROOT));
    }

    public void revokeRole(String tenantId, String userId, String roleCode) {
        Map<String, Set<String>> users = userRoles.get(tenantId);
        if (users != null) {
            Set<String> roles = users.get(userId);
            if (roles != null) {
                roles.remove(roleCode.toLowerCase(Locale.ROOT));
            }
        }
    }

    public Set<String> rolesOf(String tenantId, String userId) {
        Map<String, Set<String>> users = userRoles.get(tenantId);
        if (users == null) {
            return Set.of();
        }
        return Set.copyOf(users.getOrDefault(userId, Set.of()));
    }

    public Set<String> permissionsOfRoles(String tenantId, Set<String> roles) {
        Set<String> out = new LinkedHashSet<>();
        for (String role : roles) {
            String r = role.toLowerCase(Locale.ROOT);
            out.addAll(DEFAULT_ROLE_PERMS.getOrDefault(r, Set.of()));
            Map<String, Set<String>> extra = rolePerms.get(tenantId);
            if (extra != null) {
                out.addAll(extra.getOrDefault(r, Set.of()));
            }
        }
        return out;
    }

    public boolean hasPermission(String tenantId, String userId, Set<String> roles, String permission) {
        if (roles != null && roles.stream().anyMatch(r -> ROLE_TENANT_ADMIN.equalsIgnoreCase(r))) {
            return true;
        }
        return permissionsOfRoles(tenantId, roles == null ? Set.of() : roles).contains(permission);
    }

    public Map<String, Object> describeDefaults() {
        Map<String, Object> m = new LinkedHashMap<>();
        DEFAULT_ROLE_PERMS.forEach((k, v) -> m.put(k, v.stream().sorted().toList()));
        return m;
    }

    public List<Map<String, Object>> listUsers(String tenantId) {
        Map<String, Set<String>> users = userRoles.getOrDefault(tenantId, Map.of());
        return users.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(e -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("userId", e.getKey());
                    m.put("roles", e.getValue().stream().sorted().toList());
                    return m;
                })
                .toList();
    }

    private void requireKnownRole(String roleCode) {
        if (roleCode == null || roleCode.isBlank()) {
            throw new UnauthorizedException("role is required");
        }
        String r = roleCode.toLowerCase(Locale.ROOT);
        if (!DEFAULT_ROLE_PERMS.containsKey(r)) {
            throw new UnauthorizedException("unknown role: " + roleCode);
        }
    }
}
