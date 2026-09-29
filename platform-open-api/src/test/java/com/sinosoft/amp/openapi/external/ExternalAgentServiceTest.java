package com.sinosoft.amp.openapi.external;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExternalAgentServiceTest {

    private InMemoryStore store;
    private ExternalAgentService service;

    @BeforeEach
    void setUp() {
        store = new InMemoryStore();
        service = new ExternalAgentService(store, 1);
    }

    @Test
    void registerAndHeartbeatKeepsHealthy() {
        ExternalAgentRecord rec = service.register("acme", "ext1", "http://127.0.0.1:19000", "k", "stream");
        assertEquals(ExternalAgentRecord.HEALTHY, rec.status());
        ExternalAgentRecord after = service.heartbeat("acme", rec.id());
        assertEquals(ExternalAgentRecord.HEALTHY, after.status());
        assertEquals(1, service.listWithHealth("acme").size());
    }

    @Test
    void staleHeartbeatMarksUnhealthy() throws Exception {
        ExternalAgentRecord rec = service.register("acme", "ext1", "http://x", "k", null);
        // 强制旧心跳
        store.upsert(new ExternalAgentRecord(rec.id(), rec.tenantId(), rec.name(), rec.baseUrl(),
                rec.callbackKey(), rec.capabilities(), ExternalAgentRecord.HEALTHY,
                Instant.now().minusSeconds(3600), rec.createdAt()));
        ExternalAgentRecord listed = service.listWithHealth("acme").get(0);
        assertEquals(ExternalAgentRecord.UNHEALTHY, listed.status());
    }

    @Test
    void invalidRegisterRejected() {
        assertThrows(com.sinosoft.amp.openapi.catalog.CatalogException.class,
                () -> service.register("acme", "bad name", "http://x", null, null));
        assertThrows(com.sinosoft.amp.openapi.catalog.CatalogException.class,
                () -> service.register("acme", "ok", "ftp://x", null, null));
    }

    static class InMemoryStore implements ExternalAgentStore {
        private final Map<String, ExternalAgentRecord> byId = new HashMap<>();

        @Override
        public void upsert(ExternalAgentRecord record) {
            byId.put(record.id(), record);
        }

        @Override
        public Optional<ExternalAgentRecord> find(String tenantId, String id) {
            ExternalAgentRecord r = byId.get(id);
            return r != null && r.tenantId().equals(tenantId) ? Optional.of(r) : Optional.empty();
        }

        @Override
        public Optional<ExternalAgentRecord> findByName(String tenantId, String name) {
            return list(tenantId).stream().filter(a -> a.name().equals(name)).findFirst();
        }

        @Override
        public List<ExternalAgentRecord> list(String tenantId) {
            List<ExternalAgentRecord> out = new ArrayList<>();
            for (ExternalAgentRecord r : byId.values()) {
                if (r.tenantId().equals(tenantId)) {
                    out.add(r);
                }
            }
            return out;
        }

        @Override
        public void heartbeat(String id, Instant at, String status) {
            ExternalAgentRecord old = byId.get(id);
            if (old == null) return;
            byId.put(id, new ExternalAgentRecord(old.id(), old.tenantId(), old.name(), old.baseUrl(),
                    old.callbackKey(), old.capabilities(), status, at, old.createdAt()));
        }
    }
}
