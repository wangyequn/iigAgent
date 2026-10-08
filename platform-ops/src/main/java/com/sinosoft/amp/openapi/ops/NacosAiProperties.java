package com.sinosoft.amp.openapi.ops;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Nacos AI Registry（可选）。决策：sync-direction=nacos-to-local、agent-card=discover-only、
 * tenant-namespace 默认 shared（prefix 后置）。连接参数可与 amp.nacos.* 分离配置。
 */
@ConfigurationProperties(prefix = "amp.nacos.ai")
public record NacosAiProperties(
        boolean enabled,
        String serverAddr,
        /** shared | identity；prefix 后置 */
        String tenantNamespace,
        /** shared 模式下的 namespaceId，默认 public */
        String sharedNamespace,
        String username,
        String password,
        /** Nacos context-path，默认 /nacos */
        String contextPath,
        /** 仅 nacos→local（锁定） */
        String syncDirection,
        /** discover-only（锁定） */
        String agentCardMode,
        /** Nacos 3 Admin API serverIdentity 头名，默认 serverIdentity */
        String identityKey,
        /** serverIdentity 头值，与 NACOS_AUTH_IDENTITY_VALUE 一致 */
        String identityValue,
        /** 启动时自动 mcp/sync + agents/import */
        boolean syncOnStart,
        /** Prompt 热更（amp.agent.{id}.sys） */
        boolean promptEnabled,
        /** 发布态钉 version/stable，开发态 latest */
        boolean promptPinPublished,
        String promptStableLabel,
        String promptLatestLabel,
        String promptKeyPrefix,
        long promptCacheTtlMs,
        /** prefix 模式命名空间前缀，默认 amp-（tenant acme → amp-acme） */
        String namespacePrefix
) {
    public static final String SYNC_NACOS_TO_LOCAL = "nacos-to-local";
    public static final String AGENT_CARD_DISCOVER_ONLY = "discover-only";
    public static final String NS_SHARED = "shared";
    public static final String NS_IDENTITY = "identity";
    public static final String NS_PREFIX = "prefix";

    public String effectiveNamespace(String tenantId) {
        String mode = tenantNamespace() == null ? NS_SHARED : tenantNamespace();
        if (NS_IDENTITY.equalsIgnoreCase(mode) && tenantId != null && !tenantId.isBlank()) {
            return tenantId;
        }
        if (NS_PREFIX.equalsIgnoreCase(mode) && tenantId != null && !tenantId.isBlank()) {
            String p = namespacePrefix() == null ? "amp-" : namespacePrefix();
            return p + tenantId;
        }
        return sharedNamespace() == null || sharedNamespace().isBlank() ? "public" : sharedNamespace();
    }

    public String baseUrl() {
        String addr = serverAddr() == null || serverAddr().isBlank() ? "127.0.0.1:8848" : serverAddr();
        String normalized = addr.startsWith("http") ? addr : ("http://" + addr);
        if (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        String ctx = contextPath() == null || contextPath().isBlank() ? "/nacos" : contextPath();
        if (!ctx.startsWith("/")) {
            ctx = "/" + ctx;
        }
        return normalized + ctx;
    }
}
