package com.sinosoft.amp.openapi.ops;

import com.sinosoft.amp.common.api.ApiError;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.sinosoft.amp.openapi.security.AuthContext;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

/**
 * S7 运行时配置 + 指标快照（Prometheus 原文在 /actuator/prometheus）。
 */
@RestController
@RequestMapping("/api/v1/admin")
public class AdminOpsController {

    private final RuntimeConfigService config;
    private final MeterRegistry registry;

    public AdminOpsController(RuntimeConfigService config, MeterRegistry registry) {
        this.config = config;
        this.registry = registry;
    }

    @GetMapping("/config")
    public Map<String, Object> getConfig() {
        AuthContext.requireTenantId();
        return config.snapshot();
    }

    @PostMapping("/config")
    public Map<String, Object> updateConfig(@RequestBody Map<String, Object> patch) {
        AuthContext.requireTenantId();
        config.update(patch);
        return config.snapshot();
    }

    @GetMapping("/metrics/summary")
    public Map<String, Object> metricsSummary() {
        AuthContext.requireTenantId();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("config", config.snapshot());
        out.put("qpsHints", Map.of(
                "chatRequests", counter("amp.chat.requests"),
                "chatErrors", counter("amp.chat.errors"),
                "rateLimitRejected", (double) config.rejectedTotal()));
        Timer timer = registry.find("amp.chat.latency").timer();
        Map<String, Object> latency = new LinkedHashMap<>();
        if (timer != null) {
            latency.put("count", timer.count());
            latency.put("meanMs", timer.mean(java.util.concurrent.TimeUnit.MILLISECONDS));
            latency.put("p95Ms", timer.percentile(0.95, java.util.concurrent.TimeUnit.MILLISECONDS));
            latency.put("maxMs", timer.max(java.util.concurrent.TimeUnit.MILLISECONDS));
        } else {
            latency.put("count", 0L);
            latency.put("meanMs", 0.0);
            latency.put("p95Ms", 0.0);
            latency.put("maxMs", 0.0);
        }
        out.put("latency", latency);
        out.put("tokens", Map.of(
                "prompt", counter("amp.tokens.prompt"),
                "completion", counter("amp.tokens.completion"),
                "estCostPer1k", config.tokenCostPer1k()));
        out.put("tools", Map.of("calls", counter("amp.tool.calls")));
        out.put("hitl", Map.of(
                "confirms", counter("amp.hitl.confirms"),
                "approved", counterTag("amp.hitl.by_result", "result", "approved"),
                "rejected", counterTag("amp.hitl.by_result", "result", "rejected")));
        return out;
    }

    private double counter(String name) {
        var c = registry.find(name).counter();
        return c == null ? 0 : c.count();
    }

    private double counterTag(String name, String key, String value) {
        var c = registry.find(name).tags(key, value).counter();
        return c == null ? 0 : c.count();
    }
}
