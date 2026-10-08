package com.sinosoft.amp.openapi.ops;

import com.alibaba.nacos.api.NacosFactory;
import com.alibaba.nacos.api.config.ConfigService;
import com.alibaba.nacos.api.config.listener.Listener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;

/**
 * T2 Nacos 配置接入：拉取 + 监听，映射到 {@link RuntimeConfigService}。
 * 热更键：amp.ops.* / ops.*（rateLimitEnabled、rateLimitPerMinute、tokenCharsPerToken、tokenCostPer1k）。
 */
@Component
public class NacosConfigBridge {

    private static final Logger log = LoggerFactory.getLogger(NacosConfigBridge.class);

    private final NacosProperties properties;
    private final RuntimeConfigService config;
    private final AtomicReference<String> lastRaw = new AtomicReference<>();
    private volatile ConfigService configService;
    private volatile String source = "local";
    private volatile String lastError;

    public NacosConfigBridge(NacosProperties properties, RuntimeConfigService config) {
        this.properties = properties;
        this.config = config;
    }

    /** ApplicationRunner / 显式 init。 */
    public synchronized void start() {
        if (!properties.enabled()) {
            source = "local";
            log.info("Nacos config bridge disabled (amp.nacos.enabled=false)");
            return;
        }
        try {
            Properties p = new Properties();
            p.put("serverAddr", properties.serverAddr() == null ? "127.0.0.1:8848" : properties.serverAddr());
            if (properties.namespace() != null && !properties.namespace().isBlank()) {
                p.put("namespace", properties.namespace());
            }
            if (properties.username() != null) {
                p.put("username", properties.username());
            }
            if (properties.password() != null) {
                p.put("password", properties.password());
            }
            configService = NacosFactory.createConfigService(p);
            String dataId = properties.dataId() == null ? "amp-platform.properties" : properties.dataId();
            String group = properties.group() == null ? "DEFAULT_GROUP" : properties.group();
            String raw = configService.getConfig(dataId, group, 3000);
            applyRaw(raw);
            configService.addListener(dataId, group, new Listener() {
                @Override
                public Executor getExecutor() {
                    return null;
                }

                @Override
                public void receiveConfigInfo(String content) {
                    applyRaw(content);
                    log.info("Nacos config updated dataId={} bytes={}", dataId,
                            content == null ? 0 : content.length());
                }
            });
            source = "nacos:" + properties.serverAddr() + "/" + dataId;
            lastError = null;
            log.info("Nacos config bridge started {}", source);
        } catch (Exception e) {
            source = "local(fallback)";
            lastError = e.getMessage();
            log.warn("Nacos config bridge failed, stay on local config: {}", e.toString());
        }
    }

    /** 解析 properties / YAML 简单键值并写入 RuntimeConfig。 */
    void applyRaw(String raw) {
        lastRaw.set(raw == null ? "" : raw);
        if (raw == null || raw.isBlank()) {
            return;
        }
        Map<String, String> map = parse(raw);
        Map<String, Object> patch = new HashMap<>();
        putIfPresent(patch, map, "rateLimitEnabled", "amp.ops.rate-limit-enabled", "amp.ops.rateLimitEnabled",
                "ops.rate-limit-enabled", "rateLimitEnabled", "rate-limit-enabled");
        putIfPresent(patch, map, "rateLimitPerMinute", "amp.ops.rate-limit-per-minute", "amp.ops.rateLimitPerMinute",
                "ops.rate-limit-per-minute", "rateLimitPerMinute", "rate-limit-per-minute");
        putIfPresent(patch, map, "tokenCharsPerToken", "amp.ops.token-chars-per-token", "amp.ops.tokenCharsPerToken",
                "ops.token-chars-per-token", "tokenCharsPerToken", "token-chars-per-token");
        putIfPresent(patch, map, "tokenCostPer1k", "amp.ops.token-cost-per-1k", "amp.ops.tokenCostPer1k",
                "ops.token-cost-per-1k", "tokenCostPer1k", "token-cost-per-1k");
        if (!patch.isEmpty()) {
            config.update(patch);
            log.info("applied Nacos overrides {}", patch.keySet());
        }
    }

    public Map<String, Object> status() {
        Map<String, Object> m = new HashMap<>();
        m.put("enabled", properties.enabled());
        m.put("source", source);
        m.put("serverAddr", properties.serverAddr());
        m.put("dataId", properties.dataId());
        m.put("group", properties.group());
        m.put("namespace", properties.namespace());
        m.put("lastError", lastError);
        m.put("lastRawPreview", preview(lastRaw.get()));
        m.put("runtimeConfig", config.snapshot());
        return m;
    }

    private static String preview(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.length() > 200 ? t.substring(0, 200) + "..." : t;
    }

    private static void putIfPresent(Map<String, Object> patch, Map<String, String> map,
                                     String dest, String... keys) {
        for (String k : keys) {
            if (map.containsKey(k)) {
                String v = map.get(k);
                if ("rateLimitEnabled".equals(dest)) {
                    patch.put(dest, Boolean.parseBoolean(v));
                } else {
                    try {
                        patch.put(dest, Integer.parseInt(v.trim()));
                    } catch (NumberFormatException e) {
                        patch.put(dest, v.trim());
                    }
                }
                return;
            }
        }
    }

    /** 支持 properties 与简单 YAML（key: value，含一层缩进）。 */
    static Map<String, String> parse(String raw) {
        Map<String, String> map = new HashMap<>();
        String prefixStack = "";
        for (String line : raw.split("\n")) {
            String t = line.replace("\r", "");
            if (t.trim().isEmpty() || t.trim().startsWith("#")) {
                continue;
            }
            if (t.contains(":") && !t.trim().startsWith("-")) {
                int i = t.indexOf(':');
                String key = t.substring(0, i).trim();
                String val = t.substring(i + 1).trim();
                if (val.isEmpty()) {
                    // YAML section
                    String indent = t.substring(0, t.indexOf(key.charAt(0)));
                    prefixStack = key + ".";
                    continue;
                }
                // strip nested indent prefix for simple maps
                String full = key;
                if (!key.startsWith("amp") && !key.contains(".") && !prefixStack.isEmpty()) {
                    // keep raw key too
                }
                map.put(full, stripQuotes(val));
                map.putIfAbsent(shortKey(full), stripQuotes(val));
            } else if (t.contains("=")) {
                int i = t.indexOf('=');
                String key = t.substring(0, i).trim();
                String val = t.substring(i + 1).trim();
                map.put(key, stripQuotes(val));
                map.putIfAbsent(shortKey(key), stripQuotes(val));
            }
        }
        // also flatten known dotted keys from nested YAML-like amp.ops.rate-limit-enabled
        return map;
    }

    private static String shortKey(String key) {
        int i = key.lastIndexOf('.');
        return i >= 0 ? key.substring(i + 1) : key;
    }

    private static String stripQuotes(String v) {
        if (v == null) {
            return "";
        }
        String s = v.trim();
        if ((s.startsWith("\"") && s.endsWith("\"")) || (s.startsWith("'") && s.endsWith("'"))) {
            s = s.substring(1, s.length() - 1);
        }
        // strip YAML inline comment
        int hash = s.indexOf(" #");
        if (hash > 0) {
            s = s.substring(0, hash).trim();
        }
        return s;
    }
}
