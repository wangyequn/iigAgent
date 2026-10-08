package com.sinosoft.amp.openapi.external;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sinosoft.amp.openapi.catalog.CatalogException;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExternalChatClientTest {

    private HttpServer server;
    private int port;
    private ExternalChatClient client;
    private InMemoryStore store;
    private final AtomicReference<String> lastBody = new AtomicReference<>();

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        port = server.getAddress().getPort();
        server.createContext("/api/v1/messages", exchange -> {
            lastBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] resp = "{\"reply\":\"来自外部 Agent\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, resp.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(resp);
            }
        });
        server.start();
        store = new InMemoryStore();
        ExternalAgentService service = new ExternalAgentService(store, 90);
        client = new ExternalChatClient(service, new ObjectMapper());
        store.upsert(new ExternalAgentRecord("id1", "acme", "ext-demo",
                "http://127.0.0.1:" + port, "ck", "chat",
                ExternalAgentRecord.HEALTHY, Instant.now(), Instant.now()));
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void forwardMessageReturnsNormalizedReply() throws Exception {
        assertTrue(client.hasExternal("acme", "ext-demo"));
        Map<String, Object> out = client.forwardMessage("acme", "ext-demo", "u1", "s1", "hello");
        assertEquals("来自外部 Agent", out.get("reply"));
        assertEquals("managed".equals(out.get("source")) ? "managed" : true, true);
        assertTrue(String.valueOf(out.get("source")).startsWith("external:"));
        assertTrue(lastBody.get().contains("hello"));
    }

    @Test
    void unknownExternalThrows() {
        assertThrows(CatalogException.class,
                () -> client.forwardMessage("acme", "nope", "u", "s", "x"));
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
            return byId.values().stream().filter(a -> a.tenantId().equals(tenantId)).toList();
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
