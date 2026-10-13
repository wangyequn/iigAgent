package com.sinosoft.amp.openapi.iam;

import com.sinosoft.amp.openapi.security.AuthContext;
import com.sinosoft.amp.openapi.security.AuthProperties;
import com.sinosoft.amp.openapi.security.IamProperties;
import com.sinosoft.amp.openapi.security.JwtAuthSupport;
import com.sinosoft.amp.openapi.security.JwtIdentity;
import com.sinosoft.amp.openapi.security.JeecgIdentity;
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
        JeecgIdentity jeecg = AuthContext.currentJeecg();
        if (jeecg != null) {
            // 人轨优先：本部署 IdP=pc_platform；权限码是业务码，不能当 iam:admin 用
            if (isPlatformAdmin(jeecg) || jeecg.hasPerm(permission)) {
                return;
            }
            String userId = jeecg.username();
            Set<String> roles = effectiveJeecgRoles(tenant, jeecg);
            if (iam.hasPermission(tenant, userId, roles, permission)) {
                return;
            }
            deny(userId, "jeecg", permission, roles);
        }
        JwtIdentity jwt = JwtAuthSupport.current();
        if (jwt != null) {
            Set<String> roles = normalize(jwt.roles());
            if (roles.isEmpty()) {
                roles = Set.of(IamService.ROLE_AGENT_CALLER);
            }
            if (iam.hasPermission(tenant, jwt.userId(), roles, permission)) {
                return;
            }
            deny(jwt.userId(), "jwt", permission, roles);
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
        JeecgIdentity jeecg = AuthContext.currentJeecg();
        if (jeecg != null) {
            return effectiveJeecgRoles(tenantId, jeecg);
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
        JeecgIdentity jeecg = AuthContext.currentJeecg();
        if (jeecg != null && jeecg.username() != null && !jeecg.username().isBlank()) {
            return jeecg.username();
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

    /**
     * Jeecg 用户有效角色 = IdP 角色 + 平台 IAM 授权 + 平台管理员标记。
     * loginByToken 往往 roles 为空，权限码是业务按钮码，不能直接当 iam:admin 用。
     */
    Set<String> effectiveJeecgRoles(String tenantId, JeecgIdentity jeecg) {
        Set<String> roles = new LinkedHashSet<>(normalize(jeecg.roles()));
        roles.addAll(iam.rolesOf(tenantId, jeecg.username()));
        if (isPlatformAdmin(jeecg)) {
            roles.add(IamService.ROLE_TENANT_ADMIN);
        }
        if (roles.isEmpty()) {
            roles.add(IamService.ROLE_AGENT_CALLER);
        }
        return roles;
    }

    /**
     * pc_platform 用户/角色管理员 → 中台 tenant_admin。
     * 标记：administratorsId 角色 / 用户角色维护按钮码。
     */
    static boolean isPlatformAdmin(JeecgIdentity jeecg) {
        return jeecg.hasAnyRole("admin", "tenant_admin", "superadmin")
                || jeecg.hasPerm("iam:admin")
                || jeecg.hasPerm("platform:user:setroles")
                || jeecg.hasPerm("platform:user:role")
                || jeecg.hasPerm("platform:user:add")
                || jeecg.hasPerm("system:role:add")
                || jeecg.hasPerm("system:user:add");
    }

    private void deny(String userId, String source, String permission, Set<String> roles) {
        org.slf4j.LoggerFactory.getLogger(RbacGuard.class)
                .warn("RBAC_DENIED user={} source={} permission={} roles={}", userId, source, permission, roles);
        throw new UnauthorizedException("RBAC_DENIED: missing permission " + permission
                + " (user=" + userId + ", roles=" + roles + ")");
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
