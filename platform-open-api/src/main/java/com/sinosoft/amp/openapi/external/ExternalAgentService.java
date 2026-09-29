package com.sinosoft.amp.openapi.external;

import com.sinosoft.amp.openapi.catalog.CatalogException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * S8 External 最小集：注册 / 心跳 / 目录只读展示；超时标 unhealthy。
 * Chat 转发见 ExternalChatClient。
 */
@Service
public class ExternalAgentService {

    private static final Logger log = LoggerFactory.getLogger(ExternalAgentService.class);

    private final ExternalAgentStore store;
    private final long heartbeatTimeoutSec;

    public ExternalAgentService(
            ExternalAgentStore store,
            @Value("${amp.external.heartbeat-timeout-sec:90}") long heartbeatTimeoutSec) {
        this.store = store;
        this.heartbeatTimeoutSec = heartbeatTimeoutSec;
    }

    public ExternalAgentRecord register(String tenantId, String name, String baseUrl,
                                        String callbackKey, String capabilities) {
        if (name == null || !name.matches("[A-Za-z0-9_-]{1,64}")) {
            throw new CatalogException("EXTERNAL_NAME_INVALID", "name must match [A-Za-z0-9_-]{1,64}");
        }
        if (baseUrl == null || !baseUrl.startsWith("http")) {
            throw new CatalogException("EXTERNAL_URL_INVALID", "baseUrl must be http(s) URL");
        }
        ExternalAgentRecord rec = new ExternalAgentRecord(
                UUID.randomUUID().toString().replace("-", ""),
                tenantId, name, baseUrl, callbackKey, capabilities,
                ExternalAgentRecord.HEALTHY, Instant.now(), Instant.now());
        store.upsert(rec);
        log.info("external agent registered tenant={} name={} url={}", tenantId, name, baseUrl);
        return rec;
    }

    public List<ExternalAgentRecord> listWithHealth(String tenantId) {
        List<ExternalAgentRecord> out = new ArrayList<>();
        Instant cutoff = Instant.now().minus(Duration.ofSeconds(heartbeatTimeoutSec));
        for (ExternalAgentRecord r : store.list(tenantId)) {
            String status = r.lastHeartbeat() != null && r.lastHeartbeat().isAfter(cutoff)
                    ? ExternalAgentRecord.HEALTHY
                    : ExternalAgentRecord.UNHEALTHY;
            if (!status.equals(r.status())) {
                store.heartbeat(r.id(), r.lastHeartbeat(), status);
                r = new ExternalAgentRecord(r.id(), r.tenantId(), r.name(), r.baseUrl(),
                        r.callbackKey(), r.capabilities(), status, r.lastHeartbeat(), r.createdAt());
            }
            out.add(r);
        }
        return out;
    }

    public ExternalAgentRecord heartbeat(String tenantId, String id) {
        ExternalAgentRecord rec = store.find(tenantId, id)
                .orElseThrow(() -> new CatalogException("EXTERNAL_NOT_FOUND", "external agent not found"));
        Instant now = Instant.now();
        store.heartbeat(id, now, ExternalAgentRecord.HEALTHY);
        return new ExternalAgentRecord(rec.id(), rec.tenantId(), rec.name(), rec.baseUrl(),
                rec.callbackKey(), rec.capabilities(), ExternalAgentRecord.HEALTHY, now, rec.createdAt());
    }

    public ExternalAgentRecord requireHealthyByName(String tenantId, String name) {
        return listWithHealth(tenantId).stream()
                .filter(a -> a.name().equals(name))
                .findFirst()
                .map(a -> {
                    if (!ExternalAgentRecord.HEALTHY.equals(a.status())) {
                        throw new CatalogException("EXTERNAL_UNHEALTHY", "external agent unhealthy: " + name);
                    }
                    return a;
                })
                .orElse(null);
    }
}
