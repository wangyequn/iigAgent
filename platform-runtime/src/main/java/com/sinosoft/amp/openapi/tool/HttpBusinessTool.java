package com.sinosoft.amp.openapi.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.StringJoiner;

/**
 * 通用 HTTP 业务工具：按 business_tool.config 调用外部 HTTP，登记后即可被模型调用。
 * 继承 {@link ToolBase} 以复用 PermissionEngine 的 ALLOW/ASK/DENY。
 */
public class HttpBusinessTool extends ToolBase {

    private static final Logger log = LoggerFactory.getLogger(HttpBusinessTool.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private final HttpToolSpec spec;

    public HttpBusinessTool(HttpToolSpec spec) {
        super(ToolBase.builder()
                .name(spec.name())
                .description(spec.description())
                .inputSchema(spec.parameters())
                .readOnly(spec.readOnly())
                .concurrencySafe(true));
        this.spec = spec;
    }

    public HttpToolSpec spec() {
        return spec;
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        return Mono.fromCallable(() -> doCall(param))
                .onErrorResume(e -> {
                    log.warn("http tool {} failed: {}", spec.name(), e.toString());
                    return Mono.just(ToolResultBlock.error(spec.name(), "http tool failed: " + e.getMessage()));
                });
    }

    private ToolResultBlock doCall(ToolCallParam param) throws Exception {
        Map<String, Object> input = param.getInput() == null ? Map.of() : param.getInput();
        String method = spec.method();
        String url = spec.url();

        // GET/DELETE：参数拼 query；POST/PUT：JSON body
        boolean asQuery = "GET".equals(method) || "HEAD".equals(method) || "DELETE".equals(method);
        if (asQuery && !input.isEmpty()) {
            StringJoiner qs = new StringJoiner("&");
            for (Map.Entry<String, Object> e : input.entrySet()) {
                if (e.getValue() == null) {
                    continue;
                }
                qs.add(URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8)
                        + "=" + URLEncoder.encode(String.valueOf(e.getValue()), StandardCharsets.UTF_8));
            }
            url = url + (url.contains("?") ? "&" : "?") + qs;
        }

        HttpRequest.Builder rb = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(30));
        for (Map.Entry<String, String> h : spec.headers().entrySet()) {
            rb.header(h.getKey(), h.getValue() == null ? "" : h.getValue());
        }
        if (asQuery) {
            rb.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            rb.header("Content-Type", "application/json");
            rb.method(method, HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(new LinkedHashMap<>(input))));
        }

        HttpResponse<String> res = HTTP.send(rb.build(), HttpResponse.BodyHandlers.ofString());
        String body = res.body() == null ? "" : res.body();
        int code = res.statusCode();
        if (code < 200 || code >= 300) {
            return ToolResultBlock.error(spec.name(), "HTTP " + code + " " + truncate(body));
        }
        // 尽量包一层 JSON，便于模型读取
        ObjectNode wrap = MAPPER.createObjectNode();
        wrap.put("status", code);
        wrap.put("tool", spec.name());
        try {
            wrap.set("data", MAPPER.readTree(body));
        } catch (Exception parseFail) {
            wrap.put("data", body);
        }
        return ToolResultBlock.text(MAPPER.writeValueAsString(wrap));
    }

    private static String truncate(String s) {
        return s == null ? "" : (s.length() > 500 ? s.substring(0, 500) + "..." : s);
    }
}
