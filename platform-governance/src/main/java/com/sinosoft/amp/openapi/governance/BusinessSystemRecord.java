package com.sinosoft.amp.openapi.governance;

/**
 * 业务系统目录：智能体与工具按 system_code 划分归属。
 */
public record BusinessSystemRecord(
        String tenantId,
        String systemCode,
        String name,
        String owner,
        String contact,
        String description,
        String status
) {
    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_DISABLED = "DISABLED";
}
