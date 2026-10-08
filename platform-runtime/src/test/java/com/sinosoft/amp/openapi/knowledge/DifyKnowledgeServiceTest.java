package com.sinosoft.amp.openapi.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Dify Dataset API 客户端 / 服务集成测试（本地 HttpServer 模拟）。 */
class DifyKnowledgeServiceTest {

    private HttpServer server;
    private String baseUrl;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/datasets", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String body;
            int code = 200;
            if ("GET".equals(exchange.getRequestMethod()) && path.equals("/datasets")) {
                body = "{\"data\":[{\"id\":\"ds1\",\"name\":\"amp-knowledge\"}]}";
            } else if (path.endsWith("/document/create-by-text")) {
                body = "{\"document\":{\"id\":\"doc1\",\"name\":\"使用规范\"}}";
            } else if (path.endsWith("/documents")) {
                body = "{\"data\":[{\"id\":\"doc1\",\"name\":\"使用规范\",\"indexing_status\":\"completed\",\"created_at\":1}]}";
            } else if (path.endsWith("/retrieve")) {
                body = "{\"records\":[{\"segment\":{\"id\":\"seg1\",\"content\":\"写入使用 dangerous_write\",\"document_id\":\"doc1\",\"document\":{\"name\":\"使用规范\"},\"score\":0.9}}]}";
            } else {
                code = 404;
                body = "{}";
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(code, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    private DifyKnowledgeService service() {
        DifyProperties props = new DifyProperties(
                true, baseUrl, "dataset-test", "ds1", "amp-knowledge",
                "hybrid_search", false, false, null);
        return new DifyKnowledgeService(new DifyKnowledgeClient(props, java.net.http.HttpClient.newHttpClient(), mapper));
    }

    @Test
    void ingestSearchList() {
        DifyKnowledgeService svc = service();
        assertEquals("dify", svc.providerId());
        SimpleKnowledgeService.IngestResult r = svc.ingest("acme", "使用规范", "test", "写入使用 dangerous_write");
        assertEquals("doc1", r.documentId());
        assertEquals(1, r.chunkCount());

        List<KnowledgeStore.DocRow> docs = svc.listDocuments("acme");
        assertEquals(1, docs.size());
        assertEquals("使用规范", docs.get(0).title());

        List<SimpleKnowledgeService.Hit> hits = svc.search("acme", "下单", 3);
        assertEquals(1, hits.size());
        assertTrue(hits.get(0).text().contains("dangerous_write"));
        String cited = svc.searchWithCitations("acme", "下单", 3);
        assertTrue(cited.contains("[doc1#seg1]"));
    }

    @Test
    void propertiesNormalize() {
        DifyProperties p = new DifyProperties(true, "http://x/v1/", "k", null, null, null, null, null, null);
        assertEquals("http://x/v1", p.normalizedBaseUrl());
        assertTrue(p.isConfigured());
    }
}
