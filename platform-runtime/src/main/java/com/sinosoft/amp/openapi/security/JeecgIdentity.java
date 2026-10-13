package com.sinosoft.amp.openapi.security;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/** pc_platform 登录用户（X-Access-Token）。 */
public record JeecgIdentity(
        String username,
        String clientId,
        Set<String> perms,
        Set<String> roles
) {

    public JeecgIdentity {
        username = username == null ? "" : username.trim();
        clientId = clientId == null ? "" : clientId.trim();
        perms = normalize(perms);
        roles = normalize(roles);
    }

    public boolean hasPerm(String code) {
        return code == null || perms.contains(code.toLowerCase(Locale.ROOT));
    }

    public boolean hasAnyRole(String... names) {
        if (names == null) {
            return false;
        }
        for (String n : names) {
            if (n != null && roles.contains(n.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    static Set<String> normalize(Set<String> in) {
        if (in == null || in.isEmpty()) {
            return Set.of();
        }
        Set<String> out = new LinkedHashSet<>();
        for (String s : in) {
            if (s != null && !s.isBlank()) {
                out.add(s.trim().toLowerCase(Locale.ROOT));
            }
        }
        return Set.copyOf(out);
    }
}
