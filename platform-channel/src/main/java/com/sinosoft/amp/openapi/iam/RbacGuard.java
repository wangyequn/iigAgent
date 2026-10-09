package com.sinosoft.amp.openapi.iam;

import com.sinosoft.amp.openapi.security.AuthContext;
import com.sinosoft.amp.openapi.security.AuthProperties;
import com.sinosoft.amp.openapi.security.IamProperties;
import com.sinosoft.amp.openapi.security.JwtAuthSupport;
import com.sinosoft.amp.openapi.security.JwtIdentity;
import com.sinosoft.amp.openapi.security.UnauthorizedException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * 权限点闸门。身份源优先级：JWT &gt; API Key；自报头仅在 {@code amp.iam.trust-headers=true} 时作迁移兜底。
 * 兼容规则：
 * - API Key 含 admin scope → 视为 tenant_admin（机器运维）
 * - 否则按 JWT 角色 / IAM 授权 /（可选）X-User-Roles 校验 permission
 */
@Component
public class RbacGuard {

    public static final String HEADER_USER = "X-User-Id";
    public static final String HEADER_ROLES = "X-User-Roles";

    private final IamService iam;
    private final boolean trustHeaders;

    public RbacGuard(IamService iam) {
        this(iam, new IamProperties("", "", false));
    }

    @Autowired
    public RbacGuard(IamService iam, IamProperties iamProperties) {
        this.iam = iam;
        this.trustHeaders = iamProperties != null && iamProperties.trustHeaders();
    }

    public void require(String permission) {
        String tenant = AuthContext.requireTenantId();
        AuthProperties.KeyEntry entry = AuthContext.get();
        if (entry != null && entry.allowsScope(AuthProperties.SCOPE_ADMIN)) {
            return;
        }
        JwtIdentity jwt = JwtAuthSupport.current();
        if (jwt != null) {
            Set<String> roles = normalize(jwt.roles());
            if (roles.isEmpty()) {
                roles = Set.of(IamService.ROLE_AGENT_CALLER);
            }
            if (!iam.hasPermission(tenant, jwt.userId(), roles, permission)) {
                throw new UnauthorizedException("RBAC_DENIED: missing permission " + permission);
            }
            return;
        }
        String userId = currentUserId();
        Set<String> roles = currentRoles(tenant, userId);
        if (!iam.hasPermission(tenant, userId, roles, permission)) {
            throw new UnauthorizedException("RBAC_DENIED: missing permission " + permission);
        }
    }

    public Set<String> currentRoles(String tenantId, String userId) {
        JwtIdentity jwt = JwtAuthSupport.current();
        if (jwt != null) {
            Set<String> roles = normalize(jwt.roles());
            if (!roles.isEmpty()) {
                return roles;
            }
        }
        Set<String> roles = new LinkedHashSet<>();
        if (trustHeaders) {
            String header = header(HEADER_ROLES);
            if (header != null && !header.isBlank()) {
                Arrays.stream(header.split(","))
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .map(s -> s.toLowerCase(Locale.ROOT))
                        .forEach(roles::add);
            }
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
        JwtIdentity jwt = JwtAuthSupport.current();
        if (jwt != null && jwt.userId() != null && !jwt.userId().isBlank()) {
            return jwt.userId();
        }
        if (trustHeaders) {
            String uid = header(HEADER_USER);
            if (uid != null && !uid.isBlank()) {
                return uid.trim();
            }
        }
        return AuthContext.actorLabel();
    }

    public boolean isTrustHeaders() {
        return trustHeaders;
    }

    private static Set<String> normalize(Set<String> roles) {
        Set<String> out = new LinkedHashSet<>();
        if (roles != null) {
            for (String role : roles) {
                if (role != null && !role.isBlank()) {
                    out.add(role.toLowerCase(Locale.ROOT));
                }
            }
        }
        return out;
    }

    private String header(String name) {
        var attrs = RequestContextHolder.getRequestAttributes();
        if (attrs instanceof ServletRequestAttributes sra) {
            return sra.getRequest().getHeader(name);
        }
        return null;
    }
}
