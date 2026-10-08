package com.sinosoft.amp.openapi.governance;

/**
 * MCP 登记。source=local 为平台种子/手工；source=nacos 为 AI Registry 单向发现导入。
 * 同名冲突时 local 优先，不被 nacos 覆盖（sync-direction=nacos-to-local）。
 */
public record McpServerRecord(
        String tenantId,
        String name,
        String transport,
        String command,
        String args,
        String url,
        String enableTools,
        String status,
        String source
) {
    public static final String STATUS_REGISTERED = "REGISTERED";
    public static final String SOURCE_LOCAL = "local";
    public static final String SOURCE_NACOS = "nacos";

    public McpServerRecord {
        if (source == null || source.isBlank()) {
            source = SOURCE_LOCAL;
        }
    }

    /** 兼容旧构造：默认 source=local。 */
    public McpServerRecord(
            String tenantId, String name, String transport, String command,
            String args, String url, String enableTools, String status) {
        this(tenantId, name, transport, command, args, url, enableTools, status, SOURCE_LOCAL);
    }

    public boolean fromNacos() {
        return SOURCE_NACOS.equals(source);
    }
}
