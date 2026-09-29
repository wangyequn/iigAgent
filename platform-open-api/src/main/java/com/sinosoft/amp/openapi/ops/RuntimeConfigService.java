package com.sinosoft.amp.openapi.ops;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 运行时可热更配置（S7；Nacos 完整对接属债务，本类提供进程内开关）。
 */
@Configuration
@EnableConfigurationProperties(OpsProperties.class)
public class RuntimeConfigService {

    private final OpsProperties defaults;
    private final ConcurrentHashMap<String, Object> overrides = new ConcurrentHashMap<>();

    // 简单限流窗口状态
    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();
    private volatile long windowStartMs = System.currentTimeMillis();
    private final AtomicInteger windowCount = new AtomicInteger();
    private final AtomicLong rejectedTotal = new AtomicLong();
    private final AtomicLong allowedTotal = new AtomicLong();

    public RuntimeConfigService(OpsProperties defaults) {
        this.defaults = defaults;
    }

    public boolean rateLimitEnabled() {
        return (boolean) overrides.getOrDefault("rateLimitEnabled", defaults.rateLimitEnabled());
    }

    public int rateLimitPerMinute() {
        return (int) overrides.getOrDefault("rateLimitPerMinute", defaults.rateLimitPerMinute());
    }

    public int tokenCharsPerToken() {
        return (int) overrides.getOrDefault("tokenCharsPerToken",
                Math.max(1, defaults.tokenCharsPerToken()));
    }

    public int tokenCostPer1k() {
        return (int) overrides.getOrDefault("tokenCostPer1k", defaults.tokenCostPer1k());
    }

    public long rejectedTotal() {
        return rejectedTotal.get();
    }

    public long allowedTotal() {
        return allowedTotal.get();
    }

    public Map<String, Object> snapshot() {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("rateLimitEnabled", rateLimitEnabled());
        m.put("rateLimitPerMinute", rateLimitPerMinute());
        m.put("tokenCharsPerToken", tokenCharsPerToken());
        m.put("allowedTotal", allowedTotal.get());
        m.put("rejectedTotal", rejectedTotal.get());
        m.put("overrides", Map.copyOf(overrides));
        return m;
    }

    public void update(Map<String, Object> patch) {
        if (patch == null) {
            return;
        }
        for (Map.Entry<String, Object> e : patch.entrySet()) {
            if (e.getValue() == null) {
                overrides.remove(e.getKey());
            } else {
                overrides.put(e.getKey(), e.getValue());
            }
        }
    }

    /**
     * 固定窗口限流（按 key，如 apiKey / IP）。
     */
    public boolean tryAcquire(String key) {
        if (!rateLimitEnabled()) {
            allowedTotal.incrementAndGet();
            return true;
        }
        long now = System.currentTimeMillis();
        if (now - windowStartMs >= 60_000L) {
            synchronized (this) {
                if (now - windowStartMs >= 60_000L) {
                    windowStartMs = now;
                    windows.clear();
                }
            }
        }
        int limit = Math.max(1, rateLimitPerMinute());
        Window w = windows.computeIfAbsent(key == null ? "default" : key, k -> new Window());
        int n = w.count.incrementAndGet();
        if (n > limit) {
            rejectedTotal.incrementAndGet();
            return false;
        }
        allowedTotal.incrementAndGet();
        return true;
    }

    private static final class Window {
        final AtomicInteger count = new AtomicInteger();
    }
}
