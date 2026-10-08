package com.sinosoft.amp.openapi.ops;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Nacos Prompt 热更（nacos→local）。
 * 键约定：{keyPrefix}{agentId}.sys，默认 amp.agent.{agentId}.sys。
 * 发布态 pin：优先 version（v{N} / {N}），否则 label=stable；开发态 label=latest。
 * 失败回退调用方传入的 fallback（库内 sys_prompt）。
 */
@Service
public class NacosPromptService {

    private static final Logger log = LoggerFactory.getLogger(NacosPromptService.class);

    private final NacosAiProperties properties;
    private final NacosAiAdminClient client;
    private final ObjectMapper mapper = new ObjectMapper();

    /** key -> resolved template */
    private final ConcurrentHashMap<String, CacheEntry> cache = new ConcurrentHashMap<>();

    private record CacheEntry(String template, String version, long loadedAtMs) {
    }

    public NacosPromptService(NacosAiProperties properties, NacosAiAdminClient client) {
        this.properties = properties;
        this.client = client;
    }

    public boolean enabled() {
        return properties != null && properties.promptEnabled() && client.enabled();
    }

    public static String promptKey(String prefix, String agentId) {
        String p = prefix == null || prefix.isBlank() ? "amp.agent." : prefix;
        return p + agentId + ".sys";
    }

    /**
     * @param agentVersion 平台 agent 版本号（发布切流用）；pin 时用于取同号 prompt 版本
     * @param fallback     库内 sys_prompt / 默认提示词
     */
    public String resolveSysPrompt(String tenantId, String agentId, int agentVersion, String fallback) {
        if (!enabled() || agentId == null || agentId.isBlank()) {
            return fallback;
        }
        String ns = properties.effectiveNamespace(tenantId);
        String key = promptKey(properties.promptKeyPrefix(), agentId);
        String cacheKey = ns + "|" + key + "|" + agentVersion + "|" + properties.promptPinPublished();
        CacheEntry hit = cache.get(cacheKey);
        if (hit != null && System.currentTimeMillis() - hit.loadedAtMs() < properties.promptCacheTtlMs()) {
            return hit.template();
        }
        Optional<PromptView> view = fetch(ns, key, agentVersion);
        if (view.isEmpty() || view.get().template() == null || view.get().template().isBlank()) {
            return fallback;
        }
        PromptView p = view.get();
        cache.put(cacheKey, new CacheEntry(p.template(), p.version(), System.currentTimeMillis()));
        log.info("nacos prompt applied tenant={} agent={} key={} version={}", tenantId, agentId, key, p.version());
        return p.template();
    }

    Optional<PromptView> fetch(String ns, String key, int agentVersion) {
        if (properties.promptPinPublished() && agentVersion > 0) {
            for (String v : new String[] {"v" + agentVersion, String.valueOf(agentVersion)}) {
                Optional<PromptView> byVer = getDetail(ns, key, v, null);
                if (byVer.isPresent()) {
                    return byVer;
                }
            }
            String stable = properties.promptStableLabel();
            if (stable != null && !stable.isBlank()) {
                Optional<PromptView> byStable = getDetail(ns, key, null, stable);
                if (byStable.isPresent()) {
                    return byStable;
                }
            }
        }
        String latest = properties.promptLatestLabel();
        if (latest != null && !latest.isBlank()) {
            Optional<PromptView> byLatest = getDetail(ns, key, null, latest);
            if (byLatest.isPresent()) {
                return byLatest;
            }
        }
        // 无标签时取服务端默认（最新已发布）
        return getDetail(ns, key, null, null);
    }

    Optional<PromptView> getDetail(String ns, String key, String version, String label) {
        try {
            StringBuilder q = new StringBuilder("/v3/admin/ai/prompt/detail?promptKey=").append(enc(key));
            if (ns != null && !ns.isBlank()) {
                q.append("&namespaceId=").append(enc(ns));
            }
            if (version != null && !version.isBlank()) {
                q.append("&version=").append(enc(version));
            }
            if (label != null && !label.isBlank()) {
                q.append("&label=").append(enc(label));
            }
            JsonNode root = client.get(q.toString());
            JsonNode data = root.path("data");
            if (!data.isMissingNode() && !data.isNull()) {
                String template = data.path("template").asText(null);
                String ver = data.path("version").asText(null);
                if (template != null && !template.isBlank()) {
                    return Optional.of(new PromptView(key, ver, template));
                }
            }
        } catch (Exception e) {
            log.debug("prompt detail miss key={} version={} label={}: {}", key, version, label, e.toString());
        }
        // draft / 未上线：detail 404，改走 version/download（含 Template 代码块）
        return getFromVersionDownload(ns, key, version, label);
    }

    Optional<PromptView> getFromVersionDownload(String ns, String key, String version, String label) {
        try {
            String ver = version;
            if (ver == null || ver.isBlank()) {
                // versions 列表取首个
                StringBuilder vq = new StringBuilder("/v3/admin/ai/prompt/versions?promptKey=").append(enc(key))
                        .append("&pageNo=1&pageSize=5");
                if (ns != null && !ns.isBlank()) {
                    vq.append("&namespaceId=").append(enc(ns));
                }
                JsonNode vRoot = client.get(vq.toString());
                JsonNode items = vRoot.path("data").path("pageItems");
                if (items.isArray() && items.size() > 0) {
                    ver = items.get(0).path("version").asText(null);
                }
            }
            if (ver == null || ver.isBlank()) {
                return Optional.empty();
            }
            StringBuilder q = new StringBuilder("/v3/admin/ai/prompt/version/download?promptKey=").append(enc(key))
                    .append("&version=").append(enc(ver));
            if (ns != null && !ns.isBlank()) {
                q.append("&namespaceId=").append(enc(ns));
            }
            String raw = client.getString(q.toString());
            if (raw == null || raw.isBlank()) {
                return Optional.empty();
            }
            String template = extractTemplateBlock(raw);
            if (template == null || template.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(new PromptView(key, ver, template));
        } catch (Exception e) {
            log.debug("prompt version download miss key={} version={}: {}", key, version, e.toString());
            return Optional.empty();
        }
    }

    /** 从 version/download 的 Markdown 中提取 ``` 代码块模板。 */
    static String extractTemplateBlock(String raw) {
        if (raw == null) {
            return null;
        }
        int idx = raw.indexOf("## Template");
        if (idx < 0) {
            idx = raw.indexOf("Template");
        }
        if (idx < 0) {
            return null;
        }
        int fence = raw.indexOf("```", idx);
        if (fence < 0) {
            return null;
        }
        int start = raw.indexOf('\n', fence);
        if (start < 0) {
            return null;
        }
        start++;
        int end = raw.indexOf("```", start);
        if (end < 0) {
            return raw.substring(start).trim();
        }
        return raw.substring(start, end).trim();
    }

    /** 手动刷新缓存（Prompt 变更后调用，下一轮重建实例生效）。 */
    public void invalidate(String agentId) {
        if (agentId == null || agentId.isBlank()) {
            cache.clear();
            return;
        }
        String suffix = "|" + promptKey(properties.promptKeyPrefix(), agentId) + "|";
        cache.keySet().removeIf(k -> k.contains(suffix));
    }

    public Map<String, Object> status() {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("enabled", enabled());
        m.put("keyPrefix", properties == null ? null : properties.promptKeyPrefix());
        m.put("pinPublished", properties == null ? null : properties.promptPinPublished());
        m.put("stableLabel", properties == null ? null : properties.promptStableLabel());
        m.put("latestLabel", properties == null ? null : properties.promptLatestLabel());
        m.put("cacheSize", cache.size());
        return m;
    }

    public record PromptView(String key, String version, String template) {
    }

    private static String enc(String s) {
        return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
    }
}
