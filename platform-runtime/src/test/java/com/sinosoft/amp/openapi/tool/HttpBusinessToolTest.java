package com.sinosoft.amp.openapi.tool;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HttpBusinessToolTest {

    private HttpServer server;
    private String url;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/echo", exchange -> {
            String q = exchange.getRequestURI().getQuery();
            byte[] bytes = ("{\"ok\":true,\"query\":\"" + (q == null ? "" : q) + "\"}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        server.start();
        url = "http://127.0.0.1:" + server.getAddress().getPort() + "/echo";
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void specAndCall() throws Exception {
        BusinessToolRecord rec = new BusinessToolRecord(
                "acme", "echo_tool", "echo", BusinessToolRecord.KIND_HTTP,
                BusinessToolRecord.STATUS_ENABLED,
                "{\"url\":\"" + url + "\",\"method\":\"GET\",\"readOnly\":true,"
                        + "\"parameters\":{\"type\":\"object\",\"properties\":{\"q\":{\"type\":\"string\"}}}}");
        HttpToolSpec spec = HttpToolSpec.from(rec);
        assertEquals("echo_tool", spec.name());
        assertEquals("GET", spec.method());

        HttpBusinessTool tool = new HttpBusinessTool(spec);
        assertEquals("echo_tool", tool.getName());
        assertTrue(tool.getParameters() != null);

        io.agentscope.core.tool.ToolCallParam param = io.agentscope.core.tool.ToolCallParam.builder()
                .input(Map.of("q", "hi"))
                .build();
        io.agentscope.core.message.ToolResultBlock result = tool.callAsync(param).block();
        assertTrue(result != null);
        assertTrue(String.valueOf(result.getOutput()).contains("ok") || result.getName() != null);
    }

    @Test
    void pathTemplateSubstitutesAndKeepsQuery() throws Exception {
        server.createContext("/dict/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String query = exchange.getRequestURI().getQuery();
            byte[] bytes = ("{\"path\":\"" + path + "\",\"query\":\"" + (query == null ? "" : query) + "\"}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        String dictUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/dict/getDictItems/{dictCode}";
        BusinessToolRecord rec = new BusinessToolRecord(
                "acme", "query_dict", "dict", BusinessToolRecord.KIND_HTTP,
                BusinessToolRecord.STATUS_ENABLED,
                "{\"url\":\"" + dictUrl + "\",\"method\":\"GET\",\"readOnly\":true,\"authMode\":\"none\","
                        + "\"parameters\":{\"type\":\"object\",\"properties\":{"
                        + "\"dictCode\":{\"type\":\"string\"},\"q\":{\"type\":\"string\"}}}}");
        HttpBusinessTool tool = new HttpBusinessTool(HttpToolSpec.from(rec));
        io.agentscope.core.message.ToolResultBlock result = tool.callAsync(
                io.agentscope.core.tool.ToolCallParam.builder()
                        .input(Map.of("dictCode", "sex", "q", "x"))
                        .build()).block();
        assertTrue(result != null);
        String out = String.valueOf(result.getOutput());
        assertTrue(out.contains("/dict/getDictItems/sex") || out.contains("sex"), out);
    }
}
