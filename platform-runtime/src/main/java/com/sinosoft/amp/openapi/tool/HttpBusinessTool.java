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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/**
 * 通用 HTTP 业务工具：按 business_tool.config 调用外部 HTTP。
 * 运行时韧性：超时 / 幂等重试 / 熔断 / 响应截断与敏感字段脱敏。
 */
public class HttpBusinessTool extends ToolBase {

    private static final Logger log = LoggerFactory.getLogger(HttpBusinessTool.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private final HttpToolSpec spec;
    private final int timeoutMs;
    private final int maxRetries;
    private final Function<String, String> secretResolver;

    private static final Map<String, Circuit> CIRCUITS = new ConcurrentHashMap<>();

    public HttpBusinessTool(HttpToolSpec spec) {
        this(spec, 30_000, 0, null);
    }

    public HttpBusinessTool(HttpToolSpec spec, int timeoutMs, int maxRetries, Function<String, String> secretResolver) {
        super(ToolBase.builder()
                .name(spec.name())
                .description(spec.description())
                .inputSchema(spec.parameters())
                .readOnly(spec.readOnly())
                .concurrencySafe(true));
        this.spec = spec;
        this.timeoutMs = timeoutMs <= 0 ? 30_000 : timeoutMs;
        this.maxRetries = Math.max(0, Math.min(3, maxRetries));
        this.secretResolver = secretResolver;
    }

    public HttpToolSpec spec() {
        return spec;
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        long t0 = System.currentTimeMillis();
        return Mono.fromCallable(() -> doCallWithRetry(param))
                .doOnNext(result -> {
                    CIRCUITS.computeIfAbsent(spec.name(), n -> new Circuit()).onSuccess();
                    com.sinosoft.amp.openapi.ops.ToolCallAuditor.record(
                            spec.name(), "TOOL_HTTP_OK",
                            summary(param),
                            200, System.currentTimeMillis() - t0);
                })
                .onErrorResume(e -> {
                    CIRCUITS.computeIfAbsent(spec.name(), n -> new Circuit()).onFailure();
                    log.warn("http tool {} failed: {}", spec.name(), e.toString());
                    com.sinosoft.amp.openapi.ops.ToolCallAuditor.record(
                            spec.name(), "TOOL_HTTP_FAIL",
                            e.getMessage(), 500, System.currentTimeMillis() - t0);
                    return Mono.just(ToolResultBlock.error(spec.name(), "http tool failed: " + redact(e.getMessage())));
                });
    }

    private ToolResultBlock doCallWithRetry(ToolCallParam param) throws Exception {
        Circuit circuit = CIRCUITS.computeIfAbsent(spec.name(), n -> new Circuit());
        if (circuit.isOpen()) {
            return ToolResultBlock.error(spec.name(), "tool circuit open (recent failures); try later");
        }
        int attempts = 1 + (spec.readOnly() ? maxRetries : 0);
        RuntimeException last = null;
        for (int i = 0; i < attempts; i++) {
            try {
                return doCall(param);
            } catch (RetryableToolException e) {
                last = e;
                if (i + 1 < attempts) {
                    Thread.sleep(100L * (i + 1));
                }
            }
        }
        throw last != null ? last : new IllegalStateException("tool call failed");
    }

    private static String summary(ToolCallParam param) {
        Object input = param == null ? null : param.getInput();
        return input == null ? "{}" : redact(String.valueOf(input));
    }

    private ToolResultBlock doCall(ToolCallParam param) throws Exception {
        Map<String, Object> input = param.getInput() == null ? Map.of() : param.getInput();
        String method = spec.method();
        String url = spec.url();
        Map<String, Object> rest = new LinkedHashMap<>();

        for (Map.Entry<String, Object> e : input.entrySet()) {
            if (e.getValue() == null) {
                continue;
            }
            String placeholder = "{" + e.getKey() + "}";
            if (url.contains(placeholder)) {
                url = url.replace(placeholder,
                        URLEncoder.encode(String.valueOf(e.getValue()), StandardCharsets.UTF_8));
            } else {
                rest.put(e.getKey(), e.getValue());
            }
        }

        boolean asQuery = "GET".equals(method) || "HEAD".equals(method) || "DELETE".equals(method);
        if (asQuery && !rest.isEmpty()) {
            StringJoiner qs = new StringJoiner("&");
            for (Map.Entry<String, Object> e : rest.entrySet()) {
                qs.add(URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8)
                        + "=" + URLEncoder.encode(String.valueOf(e.getValue()), StandardCharsets.UTF_8));
            }
            url = url + (url.contains("?") ? "&" : "?") + qs;
        }

        HttpRequest.Builder rb = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofMillis(timeoutMs));
        for (Map.Entry<String, String> h : spec.headers().entrySet()) {
            String value = h.getValue() == null ? "" : h.getValue();
            if (value.startsWith("secret:")) {
                String ref = value.substring("secret:".length());
                value = secretResolver == null ? "" : String.valueOf(secretResolver.apply(ref));
            }
            rb.header(h.getKey(), value);
        }
        if (spec.meta() != null && ToolMeta.AUTH_USER_TOKEN.equals(spec.meta().authMode())) {
            String userToken = com.sinosoft.amp.openapi.security.UserTokenHolder.resolveOrSingleActive();
            if (userToken != null && !userToken.isBlank()) {
                rb.header("X-Access-Token", userToken);
                if (!spec.headers().containsKey("Authorization")) {
                    rb.header("Authorization", "Bearer " + userToken);
                }
            } else {
                return ToolResultBlock.error(spec.name(),
                        "user token missing; cannot call tool with authMode=user_token");
            }
        }
        if (asQuery) {
            rb.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            rb.header("Content-Type", "application/json");
            rb.method(method, HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(new LinkedHashMap<>(rest))));
        }

        HttpResponse<String> res;
        try {
            res = HTTP.send(rb.build(), HttpResponse.BodyHandlers.ofString());
        } catch (java.net.http.HttpTimeoutException e) {
            throw new RetryableToolException("timeout after " + timeoutMs + "ms");
        } catch (java.io.IOException e) {
            throw new RetryableToolException("io error: " + e.getMessage());
        }
        String body = res.body() == null ? "" : res.body();
        int code = res.statusCode();
        if (code >= 500 || code == 429) {
            throw new RetryableToolException("HTTP " + code);
        }
        if (code < 200 || code >= 300) {
            return ToolResultBlock.error(spec.name(), "HTTP " + code + " " + truncate(redact(body)));
        }
        ObjectNode wrap = MAPPER.createObjectNode();
        wrap.put("status", code);
        wrap.put("tool", spec.name());
        try {
            wrap.set("data", MAPPER.readTree(body));
        } catch (Exception parseFail) {
            wrap.put("data", truncate(redact(body)));
        }
        return ToolResultBlock.text(MAPPER.writeValueAsString(wrap));
    }

    static String redact(String s) {
        if (s == null) {
            return "";
        }
        return s
                .replaceAll("(?i)(\"(?:password|passwd|pwd|secret|token|api[-_]?key|authorization)\"\\s*:\\s*\")([^\"]*)(\")",
                        "$1***$3")
                .replaceAll("(?i)(Bearer\\s+)[A-Za-z0-9._-]+", "$1***");
    }

    private static String truncate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() > 2000 ? s.substring(0, 2000) + "...[truncated]" : s;
    }

    private static final class Circuit {
        private final AtomicInteger failures = new AtomicInteger();
        private final AtomicLong openUntil = new AtomicLong();

        boolean isOpen() {
            return System.currentTimeMillis() < openUntil.get();
        }

        void onSuccess() {
            failures.set(0);
            openUntil.set(0);
        }

        void onFailure() {
            int n = failures.incrementAndGet();
            if (n >= 5) {
                openUntil.set(System.currentTimeMillis() + 30_000L);
                failures.set(0);
            }
        }
    }

    private static final class RetryableToolException extends RuntimeException {
        RetryableToolException(String message) {
            super(message);
        }
    }
}
