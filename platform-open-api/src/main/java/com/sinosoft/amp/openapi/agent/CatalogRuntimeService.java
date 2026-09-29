package com.sinosoft.amp.openapi.agent;

import com.sinosoft.amp.openapi.catalog.AgentCatalogRepository;
import com.sinosoft.amp.openapi.catalog.AgentRecord;
import com.sinosoft.amp.openapi.catalog.AgentStatus;
import com.sinosoft.amp.openapi.catalog.AgentVersionRecord;
import com.sinosoft.amp.openapi.catalog.CatalogException;
import com.sinosoft.amp.openapi.catalog.EndpointRelease;
import com.sinosoft.amp.openapi.catalog.VersionStatus;
import io.agentscope.harness.agent.HarnessAgent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 目录 + Endpoint 指针 + 实例缓存的编排入口。
 * 发布：建版本 → 构建实例 → 原子切指针 → 旧版排空。
 */
@Service
public class CatalogRuntimeService {

    private static final Logger log = LoggerFactory.getLogger(CatalogRuntimeService.class);

    private final AgentCatalogRepository catalog;
    private final AgentInstanceCache instances;
    private final com.sinosoft.amp.openapi.ops.ReleaseEventBus eventBus;

    public CatalogRuntimeService(AgentCatalogRepository catalog, AgentInstanceCache instances) {
        this(catalog, instances, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public CatalogRuntimeService(
            AgentCatalogRepository catalog,
            AgentInstanceCache instances,
            com.sinosoft.amp.openapi.ops.ReleaseEventBus eventBus) {
        this.catalog = catalog;
        this.instances = instances;
        this.eventBus = eventBus;
    }

    public AgentRecord createAgent(String tenantId, String agentId, String name) {
        if (agentId == null || !agentId.matches("[A-Za-z0-9_-]{1,64}")) {
            throw new CatalogException("AGENT_ID_INVALID", "agentId must match [A-Za-z0-9_-]{1,64}");
        }
        Optional<AgentRecord> existing = catalog.findAgent(tenantId, agentId);
        if (existing.isPresent()) {
            throw new CatalogException("AGENT_EXISTS", "agent already exists: " + agentId);
        }
        AgentRecord agent = new AgentRecord(tenantId, agentId, name == null ? agentId : name,
                AgentStatus.ACTIVE, Instant.now());
        catalog.upsertAgent(agent);
        log.info("create agent tenant={} agentId={}", tenantId, agentId);
        return agent;
    }

    public AgentVersionRecord createVersion(
            String tenantId,
            String agentId,
            Integer version,
            String modelId,
            String sysPrompt,
            String workspace) {
        requireActiveAgent(tenantId, agentId);
        int next = version != null ? version : nextVersion(tenantId, agentId);
        if (catalog.findVersion(tenantId, agentId, next).isPresent()) {
            throw new CatalogException("VERSION_EXISTS", "version already exists: " + next);
        }
        AgentVersionRecord record = new AgentVersionRecord(
                tenantId, agentId, next, VersionStatus.DRAFT,
                modelId, sysPrompt, workspace, Instant.now(), null);
        catalog.upsertVersion(record);
        log.info("create draft tenant={} agentId={} version={}", tenantId, agentId, next);
        return record;
    }

    /**
     * 发布并切流（B2 最小实现）：新实例 build 成功后原子更新指针，旧版 drain。
     */
    public PublishResult publishAndSwitch(String tenantId, String agentId, int version) {
        AgentRecord agent = requireActiveAgent(tenantId, agentId);
        AgentVersionRecord target = catalog.findVersion(tenantId, agentId, version)
                .orElseThrow(() -> new CatalogException("VERSION_NOT_FOUND", "version not found: " + version));
        if (target.status() == VersionStatus.ARCHIVED) {
            throw new CatalogException("VERSION_ARCHIVED", "cannot publish archived version");
        }

        EndpointRelease current = catalog.findRelease(tenantId, agentId).orElse(null);
        int fromVersion = current == null ? 0 : current.releaseVersion();

        // 1) 构建新实例（失败则不切流）
        AgentInstance instance = instances.getOrCreate(
                tenantId, agentId, target.version(), target.modelId(), target.sysPrompt(), target.workspace());

        // 2) 原子切指针 + 旧版进入排空
        catalog.upsertRelease(tenantId, agentId, version);
        instances.switchRelease(tenantId, agentId, fromVersion, version);

        if (target.status() != VersionStatus.PUBLISHED) {
            catalog.updateVersionStatus(tenantId, agentId, version, VersionStatus.PUBLISHED);
        }
        if (fromVersion > 0 && fromVersion != version) {
            catalog.findVersion(tenantId, agentId, fromVersion).ifPresent(old -> {
                if (old.status() == VersionStatus.PUBLISHED) {
                    catalog.updateVersionStatus(tenantId, agentId, fromVersion, VersionStatus.DRAINING);
                }
            });
            log.info("release switch tenant={} agent={} v{} -> v{} (old draining)",
                    tenantId, agentId, fromVersion, version);
        } else {
            log.info("release set tenant={} agent={} v{}", tenantId, agentId, version);
        }
        instances.sweepDrained();

        // 多副本：广播切流，其他副本 drain 本地旧版实例
        if (eventBus != null) {
            eventBus.publishReleaseSwitch(tenantId, agentId, fromVersion, version);
        }

        return new PublishResult(tenantId, agentId, version, fromVersion, instance.cacheKey(), agent.status());
    }

    /**
     * 解析当前 Endpoint 指向的实例（懒加载）。
     */
    public AgentInstance resolve(String tenantId, String agentId) {
        AgentRecord agent = requireActiveAgent(tenantId, agentId);
        EndpointRelease release = catalog.findRelease(tenantId, agentId)
                .orElseThrow(() -> new CatalogException("NO_RELEASE",
                        "no published version for agent: " + agentId));
        AgentVersionRecord version = catalog.findVersion(tenantId, agentId, release.releaseVersion())
                .orElseThrow(() -> new CatalogException("VERSION_NOT_FOUND",
                        "release version missing: " + release.releaseVersion()));
        if (version.status() == VersionStatus.ARCHIVED) {
            throw new CatalogException("VERSION_ARCHIVED", "release version archived");
        }
        if (instances.isDraining(tenantId, agentId, version.version())) {
            // 指针不应指向 draining；防御性回退到最近 published
            throw new CatalogException("VERSION_DRAINING", "release version is draining");
        }
        return instances.getOrCreate(
                tenantId, agentId, version.version(), version.modelId(), version.sysPrompt(), version.workspace());
    }

    public List<AgentRecord> listAgents(String tenantId) {
        return catalog.listAgents(tenantId);
    }

    public List<AgentVersionRecord> listVersions(String tenantId, String agentId) {
        requireAgentExists(tenantId, agentId);
        return catalog.listVersions(tenantId, agentId);
    }

    public Optional<Integer> releaseVersion(String tenantId, String agentId) {
        return catalog.findRelease(tenantId, agentId).map(EndpointRelease::releaseVersion);
    }

    public AgentRecord requireActiveAgent(String tenantId, String agentId) {
        AgentRecord agent = requireAgentExists(tenantId, agentId);
        if (agent.status() != AgentStatus.ACTIVE) {
            throw new CatalogException("AGENT_DISABLED", "agent disabled: " + agentId);
        }
        return agent;
    }

    private AgentRecord requireAgentExists(String tenantId, String agentId) {
        return catalog.findAgent(tenantId, agentId)
                .orElseThrow(() -> new CatalogException("AGENT_NOT_FOUND", "agent not found: " + agentId));
    }

    private int nextVersion(String tenantId, String agentId) {
        List<AgentVersionRecord> versions = catalog.listVersions(tenantId, agentId);
        return versions.stream().mapToInt(AgentVersionRecord::version).max().orElse(0) + 1;
    }

    public record PublishResult(
            String tenantId,
            String agentId,
            int releaseVersion,
            int previousVersion,
            String instanceKey,
            AgentStatus agentStatus
    ) {
    }
}
