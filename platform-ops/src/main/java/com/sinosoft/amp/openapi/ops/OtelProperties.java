package com.sinosoft.amp.openapi.ops;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * S7 OTel（T2 观测链路）。endpoint 为空时用 logging 导出（便于本地自检）。
 */
@ConfigurationProperties(prefix = "amp.otel")
public record OtelProperties(
        boolean enabled,
        String endpoint,
        String serviceName
) {
}
