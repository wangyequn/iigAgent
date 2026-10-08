package com.sinosoft.amp.openapi.catalog;

import java.util.List;
import java.util.Optional;

public interface AgentCatalogRepository {

    Optional<AgentRecord> findAgent(String tenantId, String agentId);

    List<AgentRecord> listAgents(String tenantId);

    void upsertAgent(AgentRecord agent);

    void deleteAgent(String tenantId, String agentId);

    Optional<AgentVersionRecord> findVersion(String tenantId, String agentId, int version);

    List<AgentVersionRecord> listVersions(String tenantId, String agentId);

    void upsertVersion(AgentVersionRecord version);

    void updateVersionStatus(String tenantId, String agentId, int version, VersionStatus status);

    Optional<EndpointRelease> findRelease(String tenantId, String agentId);

    void upsertRelease(String tenantId, String agentId, int releaseVersion);
}
