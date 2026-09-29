package com.sinosoft.amp.openapi.tool;

import java.util.List;
import java.util.Optional;

/**
 * 平台登记的业务工具（目录元数据，非执行体）。
 */
public record BusinessToolRecord(
        String tenantId,
        String toolName,
        String description,
        String kind,
        String status,
        String config
) {
    public static final String KIND_IN_PROCESS = "IN_PROCESS";
    public static final String STATUS_ENABLED = "ENABLED";
}
