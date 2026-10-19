package com.sinosoft.amp.openapi.iam;

import com.sinosoft.amp.openapi.security.AuthContext;
import com.sinosoft.amp.openapi.security.AuthProperties;
import com.sinosoft.amp.openapi.security.UnauthorizedException;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * 权限点闸门（仅 API Key 机器轨）。
 * API Key 含 admin scope → 视为 tenant_admin；否则按 IAM 授权校验 permission。
 */
@Component
public class RbacGuard {

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
        if (userId != null) {
            roles.addAll(iam.rolesOf(tenantId, userId));
        }
        if (roles.isEmpty()) {
            roles.add(IamService.ROLE_AGENT_CALLER);
        }
        return roles;
    }

    public String currentUserId() {
        return AuthContext.actorLabel();
    }
}
