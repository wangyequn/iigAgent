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
}
