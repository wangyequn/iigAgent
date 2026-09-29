package com.sinosoft.amp.openapi.ops;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * S7 观测/限流配置（可被 RuntimeConfig 热更覆盖）。
 */
@ConfigurationProperties(prefix = "amp.ops")
public record OpsProperties(
        boolean rateLimitEnabled,
        int rateLimitPerMinute,
        int tokenCharsPerToken,
        int tokenCostPer1k
) {
}
