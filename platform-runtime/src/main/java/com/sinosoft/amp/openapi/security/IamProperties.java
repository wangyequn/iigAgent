package com.sinosoft.amp.openapi.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * amp.iam：人轨身份源（OIDC/JWT，如 Keycloak）。
 *
 * <ul>
 *   <li>{@code issuer-uri} / {@code jwk-set-uri} 均为空 = 兼容模式，仅 API Key</li>
 *   <li>{@code trust-headers} 默认 false——不再信任 X-User-Id / X-User-Roles 自报头</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "amp.iam")
public record IamProperties(
        String issuerUri,
        String jwkSetUri,
        boolean trustHeaders
) {

    public IamProperties {
        if (issuerUri == null) {
            issuerUri = "";
        }
        if (jwkSetUri == null) {
            jwkSetUri = "";
        }
    }

    public boolean jwtEnabled() {
        return (issuerUri != null && !issuerUri.isBlank())
                || (jwkSetUri != null && !jwkSetUri.isBlank());
    }
}
