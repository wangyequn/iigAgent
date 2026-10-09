package com.sinosoft.amp.openapi.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 从 SecurityContext 中的 JWT 解析平台身份（sub / tenant claim / 角色）。
 * 角色名与 {@code IamService} 内置 code 对齐（tenant_admin、ops、…）。
 */
public final class JwtAuthSupport {

    public static final String ROLE_PREFIX = "ROLE_";

    private JwtAuthSupport() {
    }

    /** 当前请求的 JWT 身份；非 JWT 或未认证返回 null。 */
    public static JwtIdentity current() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof JwtAuthenticationToken jwtAuth) {
            return fromJwt(jwtAuth.getToken(), jwtAuth.getAuthorities());
        }
        return null;
    }

    public static JwtIdentity fromJwt(Jwt jwt, Collection<? extends GrantedAuthority> authorities) {
        if (jwt == null) {
            return null;
        }
        String userId = jwt.getSubject();
        String tenantId = firstNonBlank(
                jwt.getClaimAsString("tenant"),
                jwt.getClaimAsString("tenantId"),
                jwt.getClaimAsString("tenant_id"),
                jwt.getClaimAsString("azp"));
        Set<String> roles = new LinkedHashSet<>();
        if (authorities != null) {
            for (GrantedAuthority authority : authorities) {
                addRole(roles, authority == null ? null : authority.getAuthority());
            }
        }
        collectRoles(roles, jwt.getClaim("roles"));
        Object realmAccess = jwt.getClaim("realm_access");
        if (realmAccess instanceof Map<?, ?> realm && realm.get("roles") != null) {
            collectRoles(roles, realm.get("roles"));
        }
        Object resourceAccess = jwt.getClaim("resource_access");
        if (resourceAccess instanceof Map<?, ?> clients) {
            for (Object value : clients.values()) {
                if (value instanceof Map<?, ?> client && client.get("roles") != null) {
                    collectRoles(roles, client.get("roles"));
                }
            }
        }
        return new JwtIdentity(userId, tenantId, roles);
    }

    static void addRole(Set<String> roles, String raw) {
        if (raw == null || raw.isBlank()) {
            return;
        }
        String role = raw.trim();
        if (role.startsWith(ROLE_PREFIX)) {
            role = role.substring(ROLE_PREFIX.length());
        }
        if (!role.isEmpty()) {
            roles.add(role.toLowerCase(Locale.ROOT));
        }
    }

    @SuppressWarnings("unchecked")
    private static void collectRoles(Set<String> roles, Object claim) {
        if (claim instanceof String s) {
            for (String part : s.split(",")) {
                addRole(roles, part);
            }
        } else if (claim instanceof Collection<?> collection) {
            for (Object item : collection) {
                if (item != null) {
                    addRole(roles, item.toString());
                }
            }
        } else if (claim instanceof Map<?, ?> map) {
            // 容错：{roles: [...]}
            Object nested = map.get("roles");
            if (nested != null) {
                collectRoles(roles, nested);
            }
        }
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    /** 便于测试：不依赖 Spring 容器时手动注入 SecurityContext。 */
    public static void setAuthenticationForTest(Authentication authentication) {
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    public static void clear() {
        SecurityContextHolder.clearContext();
    }

    static List<String> roleCodesFromAuthorities(Collection<? extends GrantedAuthority> authorities) {
        Set<String> roles = new LinkedHashSet<>();
        if (authorities != null) {
            for (GrantedAuthority authority : authorities) {
                addRole(roles, authority == null ? null : authority.getAuthority());
            }
        }
        return List.copyOf(roles);
    }
}
