package com.sinosoft.amp.openapi.ops;

import com.sinosoft.amp.openapi.agent.AgentInstanceCache;
import com.sinosoft.amp.openapi.external.ExternalAgentService;
import com.sinosoft.amp.openapi.governance.GovernanceService;
import com.sinosoft.amp.openapi.governance.McpServerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Nacos AI Registry → 本地（PG）单向同步。
 * 同步方向锁定 nacos-to-local：local 种子/手工不被覆盖；不做出站 register。
 */
@Service
public class NacosAiSyncService {

    private static final Logger log = LoggerFactory.getLogger(NacosAiSyncService.class);

    private final NacosAiProperties properties;
    private final NacosAiAdminClient client;
    private final GovernanceService governance;
    private final ExternalAgentService externalAgents;
    private final AgentInstanceCache instanceCache;
    private final com.sinosoft.amp.openapi.skill.SkillAdminService skillAdmin;

    private volatile Instant lastSyncAt;
    private volatile String lastError;
    private final Map<String, Object> lastResult = new LinkedHashMap<>();

    public NacosAiSyncService(
            NacosAiProperties properties,
            NacosAiAdminClient client,
            GovernanceService governance,
            ExternalAgentService externalAgents,
            AgentInstanceCache instanceCache,
            com.sinosoft.amp.openapi.skill.SkillAdminService skillAdmin) {
        this.properties = properties;
        this.client = client;
        this.governance = governance;
        this.externalAgents = externalAgents;
        this.instanceCache = instanceCache;
        this.skillAdmin = skillAdmin;
    }

    public Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", properties != null && properties.enabled());
        m.put("serverAddr", properties == null ? null : properties.serverAddr());
        m.put("contextPath", properties == null ? null : properties.contextPath());
        m.put("syncDirection", properties == null ? null : properties.syncDirection());
        m.put("agentCardMode", properties == null ? null : properties.agentCardMode());
        m.put("tenantNamespace", properties == null ? null : properties.tenantNamespace());
        m.put("sharedNamespace", properties == null ? null : properties.sharedNamespace());
        m.put("lastSyncAt", lastSyncAt);
        m.put("lastError", lastError);
        m.put("lastResult", lastResult);
        return m;
    }

    /**
     * 从 Nacos 拉取 MCP 并 upsert（source=nacos）。
     * enableTools 为空则跳过（与 amp.mcp.require-explicit-tools 一致）。
     */
    public Map<String, Object> syncMcp(String tenantId) {
        ensureEnabled();
        String ns = properties.effectiveNamespace(tenantId);
        Map<String, Object> result = new LinkedHashMap<>();
        List<String> imported = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        List<String> conflicts = new ArrayList<>();
        try {
            List<Map<String, Object>> items = client.listMcp(ns);
            for (Map<String, Object> item : items) {
                String name = str(item.get("name"));
                if (name == null || name.isBlank()) {
                    continue;
                }
                String id = str(item.get("id"));
                Map<String, Object> detail = client.getMcp(ns, name, id).orElse(item);
                String enableTools = NacosAiAdminClient.enabledToolsFrom(detail.get("toolSpec"));
                if (enableTools == null) {
                    enableTools = NacosAiAdminClient.enabledToolsFrom(detail.get("toolSpecification"));
                }
                if (enableTools == null || enableTools.isBlank()) {
                    skipped.add(name);
                    continue;
                }
                McpServerRecord existing = governance.listMcp(tenantId).stream()
                        .filter(m -> m.name().equals(name))
                        .findFirst()
                        .orElse(null);
                if (existing != null && !existing.fromNacos()) {
                    conflicts.add(name);
                    continue;
                }
                String transport = firstNonBlank(str(detail.get("frontProtocol")), str(detail.get("protocol")),
                        "streamable_http");
                String url = NacosAiAdminClient.urlFrom(detail);
                if (url == null) {
                    skipped.add(name);
                    continue;
                }
                McpServerRecord rec = new McpServerRecord(
                        tenantId, name, normalizeTransport(transport), null, null, url,
                        enableTools, McpServerRecord.STATUS_REGISTERED, McpServerRecord.SOURCE_NACOS);
                governance.importMcpFromNacos(rec);
                imported.add(name);
            }
            if (!imported.isEmpty()) {
                instanceCache.evictTenant(tenantId);
            }
            result.put("namespace", ns);
            result.put("imported", imported);
            result.put("skipped", skipped);
            result.put("conflictsLocalKept", conflicts);
            lastSyncAt = Instant.now();
            lastError = null;
            lastResult.clear();
            lastResult.put("mcp", result);
            log.info("nacos mcp sync tenant={} imported={} skipped={} conflicts={}",
                    tenantId, imported.size(), skipped.size(), conflicts.size());
        } catch (Exception e) {
            lastError = e.toString();
            result.put("error", lastError);
            log.warn("nacos mcp sync failed tenant={}: {}", tenantId, e.toString());
        }
        return result;
    }

    /**
     * AgentCard 导入为 external_agent（discover-only）。
     * 不写入 callbackKey（密钥不来自 Nacos）。
     * 列表项无 url 时补拉详情。
     */
    public Map<String, Object> importAgents(String tenantId) {
        return importAgents(tenantId, null);
    }

    public Map<String, Object> importAgents(String tenantId, String namePrefix) {
        ensureEnabled();
        String ns = properties.effectiveNamespace(tenantId);
        Map<String, Object> result = new LinkedHashMap<>();
        List<String> imported = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        try {
            List<Map<String, Object>> items = client.listAgents(ns, namePrefix);
            for (Map<String, Object> item : items) {
                String rawName = str(item.get("name") == null ? item.get("agentName") : item.get("name"));
                String name = sanitizeName(rawName);
                String url = firstNonBlank(str(item.get("url")), NacosAiAdminClient.urlFrom(item));
                if (url == null && rawName != null) {
                    Map<String, Object> detail = client.getAgent(ns, rawName, str(item.get("version"))).orElse(null);
                    if (detail != null) {
                        url = firstNonBlank(str(detail.get("url")), NacosAiAdminClient.urlFrom(detail));
                        // list 无 url 时用 detail
                    }
                }
                if (name == null || url == null || !url.startsWith("http")) {
                    skipped.add(rawName + ":no-url-or-name");
                    continue;
                }
                try {
                    externalAgents.register(tenantId, name, url, null,
                            "{\"source\":\"nacos\",\"protocol\":\"discover-only\"}");
                    imported.add(name);
                } catch (Exception ex) {
                    skipped.add(name + ":" + ex.getMessage());
                }
            }
            result.put("namespace", ns);
            result.put("scannedPrefix", namePrefix);
            result.put("imported", imported);
            result.put("skipped", skipped);
            result.put("note", "discover-only; callbackKey never taken from Nacos");
            lastSyncAt = Instant.now();
            lastError = null;
            lastResult.put("agents", result);
        } catch (Exception e) {
            lastError = e.toString();
            result.put("error", lastError);
            log.warn("nacos agent import failed tenant={}: {}", tenantId, e.toString());
        }
        return result;
    }

    private void ensureEnabled() {
        if (!client.enabled()) {
            throw new IllegalStateException("Nacos AI Registry disabled (amp.nacos.ai.enabled=false)");
        }
    }

    /**
     * Skill 导入（DRAFT，source=nacos）→ 仍须平台 approve 才进市场。
     * Nacos Skill API 需 ≥3.2；3.1 将报错并计入 skipped。
     */
    public Map<String, Object> importSkills(String tenantId, String namePrefix) {
        ensureEnabled();
        String ns = properties.effectiveNamespace(tenantId);
        Map<String, Object> result = new LinkedHashMap<>();
        List<String> imported = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        try {
            List<Map<String, Object>> items = client.listSkills(ns, namePrefix);
            for (Map<String, Object> item : items) {
                String name = str(item.get("name") == null ? item.get("skillName") : item.get("name"));
                String version = str(item.get("version"));
                if (name == null || name.isBlank()) {
                    continue;
                }
                String content = firstNonBlank(
                        str(item.get("skillContent")),
                        str(item.get("content")),
                        str(item.get("skillMd")),
                        str(item.get("template")));
                if (content == null) {
                    Map<String, Object> detail = client.getSkill(ns, name, version).orElse(null);
                    if (detail != null) {
                        content = firstNonBlank(
                                str(detail.get("skillContent")),
                                str(detail.get("skillMd")),
                                str(detail.get("content")),
                                str(detail.get("template")));
                    }
                }
                if (content == null || content.isBlank()) {
                    // Nacos 3.2：/skills/version 含 skillMd
                    try {
                        Map<String, Object> verDetail = client.getSkillVersion(ns, name, version).orElse(null);
                        if (verDetail != null) {
                            content = firstNonBlank(str(verDetail.get("skillMd")), str(verDetail.get("skillContent")));
                            if (version == null || version.isBlank()) {
                                version = str(verDetail.get("version"));
                            }
                        }
                    } catch (Exception ignored) {
                        // fall through
                    }
                }
                if (content == null || content.isBlank()) {
                    content = "# skill " + name + "\n# imported from Nacos; attach content before approve\n";
                }
                try {
                    String desc = str(item.get("description"));
                    if (desc != null && desc.length() > 500) {
                        desc = desc.substring(0, 500);
                    }
                    skillAdmin.upload(tenantId, sanitizeName(name),
                            version == null ? "1.0.0" : version,
                            desc,
                            content, "nacos-import", "nacos");
                    imported.add(name);
                } catch (Exception ex) {
                    skipped.add(name + ":" + shortErr(ex));
                }
            }
            result.put("namespace", ns);
            result.put("imported", imported);
            result.put("skipped", skipped);
            result.put("note", "skills imported as DRAFT source=nacos; approve required");
            lastSyncAt = Instant.now();
            lastError = null;
            lastResult.put("skills", result);
        } catch (Exception e) {
            lastError = e.toString();
            result.put("error", lastError);
            result.put("note", "Skill API may require Nacos >=3.2");
            log.warn("nacos skill import failed tenant={}: {}", tenantId, e.toString());
        }
        return result;
    }

    private static String normalizeTransport(String t) {
        if (t == null) {
            return "streamable_http";
        }
        String v = t.trim().toLowerCase();
        return switch (v) {
            case "stdio", "sse", "streamable_http", "streamablehttp", "http", "websocket" -> {
                if (v.startsWith("streamable") || v.equals("http")) {
                    yield "streamable_http";
                }
                yield v.equals("websocket") ? "streamable_http" : v;
            }
            default -> "streamable_http";
        };
    }

    /** External name 仅 [A-Za-z0-9_-]{1,64}。 */
    static String sanitizeName(String name) {
        if (name == null) {
            return null;
        }
        String s = name.trim().replaceAll("[^A-Za-z0-9_-]", "_");
        if (s.isEmpty()) {
            return null;
        }
        return s.length() > 64 ? s.substring(0, 64) : s;
    }

    private static String shortErr(Exception ex) {
        String m = ex.getMessage();
        if (m == null) {
            return ex.getClass().getSimpleName();
        }
        return m.length() > 160 ? m.substring(0, 160) + "..." : m;
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static String firstNonBlank(String... vals) {
        for (String v : vals) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }

    public Optional<String> lastError() {
        return Optional.ofNullable(lastError);
    }
}
