package com.sinosoft.amp.openapi.security;

import java.util.Set;

/**
 * JWT 人轨身份。机器轨仍为 {@link AuthProperties.KeyEntry}。
 * 双轨优先级：JWT &gt; API Key（同一请求通常只携带一种 Bearer）。
 */
public record JwtIdentity(
        String userId,
        String tenantId,
        Set<String> roles
) {

    public JwtIdentity {
        roles = roles == null ? Set.of() : Set.copyOf(roles);
    }
}
