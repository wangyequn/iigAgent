package com.sinosoft.amp.openapi.ops;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * T8 Loki 日志检索（可选）。enabled=false 时只用本地日志 grep 兜底。
 */
@ConfigurationProperties(prefix = "amp.loki")
public record LokiProperties(
        boolean enabled,
        String url,
        String username,
        String password,
        String app,
        String localLogFile
) {
}
