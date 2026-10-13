package com.sinosoft.amp.openapi.tool;

import java.time.Instant;
import java.util.List;

/**
 * 平台登记的业务工具（目录元数据，非执行体）。
 * <p>
 * 生命周期：{@code DRAFT → ENABLED → DISABLED → DEPRECATED}。
 * {@code status} 同时服务运行时挂载过滤（仅 ENABLED 进 Toolkit）。
 */
public record BusinessToolRecord(
        String tenantId,
        String toolName,
        String description,
        String kind,
        String status,
        String config,
        Integer version,
        String env,
        String owner,
        String secretRef,
        Integer timeoutMs,
        Integer maxRetries,
        Integer rateLimitQps,
        String tags,
        String updatedBy,
        Instant updatedAt,
        Integer configSchemaVer,
        String deprecatedReason,
        String systemCode
) {
    public static final String KIND_IN_PROCESS = "IN_PROCESS";
    public static final String KIND_HTTP = "HTTP";
    public static final String KIND_MCP = "MCP";

    public static final String STATUS_DRAFT = "DRAFT";
    public static final String STATUS_ENABLED = "ENABLED";
    public static final String STATUS_DISABLED = "DISABLED";
    public static final String STATUS_DEPRECATED = "DEPRECATED";

    public static final String ENV_PROD = "prod";

    /** 兼容旧六参构造（测试 / 简单登记）。 */
    public BusinessToolRecord(
            String tenantId, String toolName, String description, String kind, String status, String config) {
        this(tenantId, toolName, description, kind, status, config,
                1, ENV_PROD, null, null,
                30_000, 0, null, null, null, null, 1, null, null);
    }

    public boolean isMountable() {
        return STATUS_ENABLED.equalsIgnoreCase(status);
    }

    public int timeoutMsOr(int fallback) {
        return timeoutMs == null || timeoutMs <= 0 ? fallback : timeoutMs;
    }

    public int maxRetriesOr(int fallback) {
        return maxRetries == null || maxRetries < 0 ? fallback : maxRetries;
    }

    public List<String> tagList() {
        if (tags == null || tags.isBlank()) {
            return List.of();
        }
        return List.of(tags.split("[,\\s]+")).stream().filter(s -> !s.isBlank()).toList();
    }
}
