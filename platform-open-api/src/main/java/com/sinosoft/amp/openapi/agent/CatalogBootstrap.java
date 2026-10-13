package com.sinosoft.amp.openapi.agent;

import com.sinosoft.amp.openapi.ops.NacosAiProperties;
import com.sinosoft.amp.openapi.ops.NacosAiSyncService;
import com.sinosoft.amp.openapi.ops.NacosConfigBridge;
import com.sinosoft.amp.openapi.ops.OtelBridge;
import com.sinosoft.amp.openapi.ops.ReadinessSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 运维启动钩子：就绪检查 / Nacos / OTel / 可选 Nacos AI 同步。
 * <p>
 * <b>不再播种任何 Agent / 版本 / 发布指针 / 工具 / 权限 / 知识 / 技能。</b>
 * 智能体一律经管理 API（{@code /api/v1/admin/agents...}）或数据库写入，
 * 参见 {@code scripts/provision_agents.py} 与 {@code db/seed-agents.sql}。
 */
@Component
public class CatalogBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(CatalogBootstrap.class);

    private final String defaultTenantId;
    private final ReadinessSupport readiness;
    private final NacosConfigBridge nacosConfigBridge;
    private final OtelBridge otelBridge;
    private final NacosAiSyncService nacosAiSync;
    private final NacosAiProperties nacosAiProperties;

    public CatalogBootstrap(
            @Value("${amp.auth.keys[0].tenant-id:acme}") String defaultTenantId,
            ReadinessSupport readiness,
            NacosConfigBridge nacosConfigBridge,
            OtelBridge otelBridge,
            NacosAiSyncService nacosAiSync,
            NacosAiProperties nacosAiProperties) {
        this.defaultTenantId = defaultTenantId;
        this.readiness = readiness;
        this.nacosConfigBridge = nacosConfigBridge;
        this.otelBridge = otelBridge;
        this.nacosAiSync = nacosAiSync;
        this.nacosAiProperties = nacosAiProperties;
    }

    @Override
    public void run(ApplicationArguments args) {
        readiness.logStartupCheck();
        nacosConfigBridge.start();
        otelBridge.start();
        maybeSyncNacosAi();
        String tenantId = defaultTenantId == null || defaultTenantId.isBlank() ? "acme" : defaultTenantId;
        System.setProperty("amp.default.tenant", tenantId);
        log.info("ops bootstrap done tenant={} (no catalog seed; provision via admin API or db/seed-agents.sql)",
                tenantId);
    }

    /** amp.nacos.ai.sync-on-start=true 时启动拉取 MCP / AgentCard（失败不阻断应用）。 */
    private void maybeSyncNacosAi() {
        try {
            if (nacosAiProperties == null || !nacosAiProperties.enabled() || !nacosAiProperties.syncOnStart()) {
                return;
            }
            String tenantId = defaultTenantId == null || defaultTenantId.isBlank() ? "acme" : defaultTenantId;
            Object mcp = nacosAiSync.syncMcp(tenantId);
            Object agents = nacosAiSync.importAgents(tenantId);
            log.info("nacos ai sync-on-start done tenant={} mcp={} agents={}", tenantId, mcp, agents);
        } catch (RuntimeException e) {
            log.warn("nacos ai sync-on-start skipped: {}", e.toString());
        }
    }
}
