package com.sinosoft.amp.openapi.iam;

import com.sinosoft.amp.openapi.security.AuthContext;
import com.sinosoft.amp.openapi.security.AuthProperties;
import com.sinosoft.amp.openapi.security.UnauthorizedException;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * 权限点闸门。兼容规则：
 * - API Key 含 admin scope → 视为 tenant_admin（机器运维）
 * - 否则按 X-User-Roles 或 Key 默认 agent_caller 校验 permission
 */
@Component
public class RbacGuard {

    public static final String HEADER_USER = "X-User-Id";
    public static final String HEADER_ROLES = "X-User-Roles";

    private final IamService iam;

    public RbacGuard(IamService iam) {
        this.iam = iam;
    }

    public void require(String permission) {
        String tenant = AuthContext.requireTenantId();
        AuthProperties.KeyEntry entry = AuthContext.get();
        if (entry != null && entry.allowsScope(AuthProperties.SCOPE_ADMIN)) {
            return;
        }
        String userId = currentUserId();
        Set<String> roles = currentRoles(tenant, userId);
        if (!iam.hasPermission(tenant, userId, roles, permission)) {
            throw new UnauthorizedException("RBAC_DENIED: missing permission " + permission);
        }
    }

    public Set<String> currentRoles(String tenantId, String userId) {
        Set<String> roles = new LinkedHashSet<>();
        String header = header(HEADER_ROLES);
        if (header != null && !header.isBlank()) {
            Arrays.stream(header.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .map(s -> s.toLowerCase(Locale.ROOT))
                    .forEach(roles::add);
        }
        if (roles.isEmpty() && userId != null) {
            roles.addAll(iam.rolesOf(tenantId, userId));
        }
        if (roles.isEmpty()) {
            roles.add(IamService.ROLE_AGENT_CALLER);
        }
        return roles;
    }

    public String currentUserId() {
        String uid = header(HEADER_USER);
        if (uid != null && !uid.isBlank()) {
            return uid.trim();
        }
        return AuthContext.actorLabel();
    }

    private String header(String name) {
        var attrs = RequestContextHolder.getRequestAttributes();
        if (attrs instanceof ServletRequestAttributes sra) {
            return sra.getRequest().getHeader(name);
        }
        return null;
    }
}
