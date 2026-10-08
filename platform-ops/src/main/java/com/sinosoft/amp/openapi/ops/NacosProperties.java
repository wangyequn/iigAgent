package com.sinosoft.amp.openapi.ops;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * S7/T2 Nacos 配置中心（可选）。
 * enabled=false 时不初始化客户端，沿用本地/环境配置。
 */
@ConfigurationProperties(prefix = "amp.nacos")
public record NacosProperties(
        boolean enabled,
        String serverAddr,
        String namespace,
        String group,
        String dataId,
        String username,
        String password
) {
}
