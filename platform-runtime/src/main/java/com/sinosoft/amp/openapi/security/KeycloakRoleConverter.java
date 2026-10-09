package com.sinosoft.amp.openapi.security;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Keycloak / OIDC 角色 → {@code ROLE_*}。
 * 支持 {@code roles}、{@code realm_access.roles}、{@code resource_access.*.roles}。
 * 角色 code 与 {@code IamService} 对齐（tenant_admin、developer…）。
 */
public class KeycloakRoleConverter implements Converter<Jwt, Collection<GrantedAuthority>> {

    @Override
    public Collection<GrantedAuthority> convert(Jwt jwt) {
        Set<String> roles = new LinkedHashSet<>();
        collect(roles, jwt.getClaim("roles"));
        Object realmAccess = jwt.getClaim("realm_access");
        if (realmAccess instanceof Map<?, ?> realm) {
            collect(roles, realm.get("roles"));
        }
        Object resourceAccess = jwt.getClaim("resource_access");
        if (resourceAccess instanceof Map<?, ?> clients) {
            for (Object value : clients.values()) {
                if (value instanceof Map<?, ?> client) {
                    collect(roles, client.get("roles"));
                }
            }
        }
        List<GrantedAuthority> authorities = new ArrayList<>();
        for (String role : roles) {
            authorities.add(new SimpleGrantedAuthority(JwtAuthSupport.ROLE_PREFIX + role));
        }
        return authorities;
    }

    private void collect(Set<String> roles, Object claim) {
        if (claim instanceof String s) {
            for (String part : s.split(",")) {
                add(roles, part);
            }
        } else if (claim instanceof Collection<?> collection) {
            for (Object item : collection) {
                if (item != null) {
                    add(roles, item.toString());
                }
            }
        }
    }

    private void add(Set<String> roles, String raw) {
        if (raw == null || raw.isBlank()) {
            return;
        }
        String role = raw.trim();
        if (role.startsWith(JwtAuthSupport.ROLE_PREFIX)) {
            role = role.substring(JwtAuthSupport.ROLE_PREFIX.length());
        }
        if (!role.isEmpty()) {
            roles.add(role.toLowerCase(Locale.ROOT));
        }
    }
}
