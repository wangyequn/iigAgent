package com.sinosoft.amp.openapi.external;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ExternalAgentStore {

    void upsert(ExternalAgentRecord record);

    Optional<ExternalAgentRecord> find(String tenantId, String id);

    Optional<ExternalAgentRecord> findByName(String tenantId, String name);

    List<ExternalAgentRecord> list(String tenantId);

    void heartbeat(String id, Instant at, String status);
}
