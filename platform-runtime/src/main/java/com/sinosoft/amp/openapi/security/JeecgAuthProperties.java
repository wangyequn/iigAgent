package com.sinosoft.amp.openapi.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * amp.jeecg：pc_platform（Jeecg）模式 B（OAuth2 code 换票）+ loginByToken 认票 + 平台权限码。
 *
 * <ul>
 *   <li>登录：{@code /sys/oauth2Server/getCode} → {@code /sys/oauth2Server/getTokenByCode}</li>
 *   <li>认票：{@code /sys/login/loginByToken}</li>
 *   <li>权限：{@code /sys/permission/getUserPermissionByToken}（auth 数组为权限码）</li>
 * </ul>
 * 与 pc_platform 同网关同域时 baseUrl 可写网关前缀。
 */
@ConfigurationProperties(prefix = "amp.jeecg")
public record JeecgAuthProperties(
        boolean enabled,
        String baseUrl,
        String portalUrl,
        String clientId,
        String clientSecret,
        String tokenHeader,
        String getCodePath,
        String getTokenPath,
        String loginByTokenPath,
        String permissionPath,
        String tenantId
) {

    public static final String DEFAULT_HEADER = "X-Access-Token";

    public JeecgAuthProperties {
        if (baseUrl == null) {
            baseUrl = "";
        }
        if (portalUrl == null || portalUrl.isBlank()) {
            portalUrl = "http://127.0.0.1:3200";
        }
        if (clientId == null || clientId.isBlank()) {
            // 登录平台 sys_sub_system 注册的 client_id（与前端 sso/PlatformLogin 默认一致）
            clientId = "216f0175b84541759b37cb1fca6ac02c";
        }
        if (clientSecret == null) {
            clientSecret = "";
        }
        if (tokenHeader == null || tokenHeader.isBlank()) {
            tokenHeader = DEFAULT_HEADER;
        }
        if (getCodePath == null || getCodePath.isBlank()) {
            getCodePath = "/sys/oauth2Server/getCode";
        }
        if (getTokenPath == null || getTokenPath.isBlank()) {
            getTokenPath = "/sys/oauth2Server/getTokenByCode";
        }
        if (loginByTokenPath == null || loginByTokenPath.isBlank()) {
            // 实测 pc_platform：POST /sys/loginByToken（非 /sys/login/loginByToken）
            loginByTokenPath = "/sys/loginByToken";
        }
        if (permissionPath == null || permissionPath.isBlank()) {
            permissionPath = "/sys/permission/getUserPermissionByToken";
        }
        if (tenantId == null || tenantId.isBlank()) {
            tenantId = "acme";
        }
    }

    public boolean remote() {
        return baseUrl != null && !baseUrl.isBlank();
    }
}
