package com.sinosoft.amp.openapi.catalog;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * 单测用内存目录实现（不依赖 PG）。
 */
public class InMemoryAgentCatalogRepository implements AgentCatalogRepository {

    private final Map<String, AgentRecord> agents = new HashMap<>();
    private final Map<String, AgentVersionRecord> versions = new HashMap<>();
    private final Map<String, EndpointRelease> releases = new HashMap<>();

    private static String aKey(String tenantId, String agentId) {
        return tenantId + "|" + agentId;
    }

    private static String vKey(String tenantId, String agentId, int version) {
        return tenantId + "|" + agentId + "|" + version;
    }

    @Override
    public Optional<AgentRecord> findAgent(String tenantId, String agentId) {
        return Optional.ofNullable(agents.get(aKey(tenantId, agentId)));
    }

    @Override
    public List<AgentRecord> listAgents(String tenantId) {
        List<AgentRecord> list = new ArrayList<>();
        for (AgentRecord a : agents.values()) {
            if (a.tenantId().equals(tenantId)) {
                list.add(a);
            }
        }
        list.sort((x, y) -> x.agentId().compareToIgnoreCase(y.agentId()));
        return list;
    }

    @Override
    public void upsertAgent(AgentRecord agent) {
        agents.put(aKey(agent.tenantId(), agent.agentId()), agent);
    }

    @Override
    public Optional<AgentVersionRecord> findVersion(String tenantId, String agentId, int version) {
        return Optional.ofNullable(versions.get(vKey(tenantId, agentId, version)));
    }

    @Override
    public List<AgentVersionRecord> listVersions(String tenantId, String agentId) {
        List<AgentVersionRecord> list = new ArrayList<>();
        for (AgentVersionRecord v : versions.values()) {
            if (v.tenantId().equals(tenantId) && v.agentId().equals(agentId)) {
                list.add(v);
            }
        }
        list.sort((x, y) -> Integer.compare(x.version(), y.version()));
        return list;
    }

    @Override
    public void upsertVersion(AgentVersionRecord version) {
        versions.put(vKey(version.tenantId(), version.agentId(), version.version()), version);
    }

    @Override
    public void updateVersionStatus(String tenantId, String agentId, int version, VersionStatus status) {
        AgentVersionRecord existing = versions.get(vKey(tenantId, agentId, version));
        if (existing == null) {
            return;
        }
        java.time.Instant publishedAt = existing.publishedAt();
        if (status == VersionStatus.PUBLISHED && publishedAt == null) {
            publishedAt = java.time.Instant.now();
        }
        versions.put(vKey(tenantId, agentId, version),
                new AgentVersionRecord(
                        existing.tenantId(), existing.agentId(), existing.version(), status,
                        existing.modelId(), existing.sysPrompt(), existing.workspace(),
                        existing.createdAt(), publishedAt));
    }

    @Override
    public Optional<EndpointRelease> findRelease(String tenantId, String agentId) {
        return Optional.ofNullable(releases.get(aKey(tenantId, agentId)));
    }

    @Override
    public void upsertRelease(String tenantId, String agentId, int releaseVersion) {
        releases.put(aKey(tenantId, agentId),
                new EndpointRelease(tenantId, agentId, releaseVersion, java.time.Instant.now()));
    }

    public void clear() {
        agents.clear();
        versions.clear();
        releases.clear();
    }

    @SuppressWarnings("unused")
    private static String norm(String s) {
        return s == null ? null : s.toLowerCase(Locale.ROOT);
    }
}
