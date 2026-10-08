package com.sinosoft.amp.openapi.sandbox;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * T7 Docker 沙箱（prod 默认关，按需开启）。IsolationScope 仅 USER/SESSION。
 */
@ConfigurationProperties(prefix = "amp.sandbox")
public record SandboxProperties(
        boolean enabled,
        String image,
        String dockerBin,
        String isolationScope,
        int timeoutSec,
        long memoryMb,
        double cpus,
        boolean networkNone
) {
}
