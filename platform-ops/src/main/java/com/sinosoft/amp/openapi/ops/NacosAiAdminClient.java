package com.sinosoft.amp.openapi.ops;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Nacos 3.x AI 运维 API 薄客户端（只读发现）。
 * 路径对齐官方：/v3/admin/ai/mcp/list、/v3/admin/ai/mcp、/v3/admin/ai/a2a/list、/v3/admin/ai/a2a。
 * 仅用于 nacos→local 导入；不做出站 register。
 */
@Component
public class NacosAiAdminClient {

    private static final Logger log = LoggerFactory.getLogger(NacosAiAdminClient.class);

    private final NacosAiProperties properties;
    private final ObjectMapper mapper;
    private final HttpClient http;

    public NacosAiAdminClient(NacosAiProperties properties, ObjectMapper mapper) {
        this.properties = properties;
        this.mapper = mapper;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    }

    public boolean enabled() {
        return properties != null && properties.enabled();
    }

    /** MCP 列表（pageItems）。 */
    public List<Map<String, Object>> listMcp(String namespaceId) throws Exception {
        JsonNode root = get("/v3/admin/ai/mcp/list?pageNo=1&pageSize=100&namespaceId="
                + enc(namespaceId) + "&search=blur");
        List<Map<String, Object>> out = new ArrayList<>();
        JsonNode items = root.path("data").path("pageItems");
        if (items.isArray()) {
            for (JsonNode n : items) {
                out.add(toMap(n));
            }
        }
        return out;
    }

    /** MCP 详情（含 toolSpec / localServerConfig / remoteServerConfig）。 */
    public Optional<Map<String, Object>> getMcp(String namespaceId, String mcpName, String mcpId) throws Exception {
        StringBuilder q = new StringBuilder("/v3/admin/ai/mcp?namespaceId=").append(enc(namespaceId))
                .append("&mcpName=").append(enc(mcpName));
        if (mcpId != null && !mcpId.isBlank()) {
            q.append("&mcpId=").append(enc(mcpId));
        }
        JsonNode root = get(q.toString());
        JsonNode data = root.path("data");
        if (data.isMissingNode() || data.isNull()) {
            return Optional.empty();
        }
        return Optional.of(toMap(data));
    }

    /** AgentCard 列表（discover-only）。Nacos 3.1：search=blur 必须带非空 agentName，否则返回空。 */
    public List<Map<String, Object>> listAgents(String namespaceId) throws Exception {
        return listAgents(namespaceId, null);
    }

    /** Skill 列表（需 Nacos ≥3.2；3.1 返回 404 时由调用方记录）。 */
    public List<Map<String, Object>> listSkills(String namespaceId, String namePrefix) throws Exception {
        Map<String, Map<String, Object>> byName = new LinkedHashMap<>();
        if (namePrefix != null && !namePrefix.isBlank()) {
            collectSkills(namespaceId, namePrefix, byName);
        } else {
            for (String p : SCAN_PREFIXES) {
                collectSkills(namespaceId, p, byName);
            }
        }
        return new ArrayList<>(byName.values());
    }

    private void collectSkills(String namespaceId, String namePrefix, Map<String, Map<String, Object>> out)
            throws Exception {
        JsonNode root = get("/v3/admin/ai/skills/list?pageNo=1&pageSize=100&namespaceId="
                + enc(namespaceId) + "&search=blur" + (namePrefix == null || namePrefix.isBlank()
                        ? "" : "&name=" + enc(namePrefix)));
        JsonNode items = root.path("data").path("pageItems");
        if (!items.isArray()) {
            items = root.path("data").path("items");
        }
        if (items.isArray()) {
            for (JsonNode n : items) {
                Map<String, Object> m = toMap(n);
                Object name = firstOf(m, "name", "skillName");
                if (name != null) {
                    out.putIfAbsent(String.valueOf(name), m);
                }
            }
        }
    }

    public Optional<Map<String, Object>> getSkill(String namespaceId, String skillName, String version)
            throws Exception {
        StringBuilder q = new StringBuilder("/v3/admin/ai/skills?namespaceId=").append(enc(namespaceId))
                .append("&skillName=").append(enc(skillName));
        if (version != null && !version.isBlank()) {
            q.append("&version=").append(enc(version));
        }
        JsonNode root = get(q.toString());
        JsonNode data = root.path("data");
        if (data.isMissingNode() || data.isNull()) {
            // 兼容 name=
            q = new StringBuilder("/v3/admin/ai/skills?namespaceId=").append(enc(namespaceId))
                    .append("&name=").append(enc(skillName));
            root = get(q.toString());
            data = root.path("data");
        }
        if (data.isMissingNode() || data.isNull()) {
            return Optional.empty();
        }
        return Optional.of(toMap(data));
    }

    /** Skill 版本详情（含 skillMd）。 */
    public Optional<Map<String, Object>> getSkillVersion(String namespaceId, String skillName, String version)
            throws Exception {
        StringBuilder q = new StringBuilder("/v3/admin/ai/skills/version?namespaceId=").append(enc(namespaceId))
                .append("&skillName=").append(enc(skillName));
        if (version != null && !version.isBlank()) {
            q.append("&version=").append(enc(version));
        }
        JsonNode root = get(q.toString());
        JsonNode data = root.path("data");
        if (data.isMissingNode() || data.isNull()) {
            return Optional.empty();
        }
        return Optional.of(toMap(data));
    }

    private static Object firstOf(Map<String, Object> m, String... keys) {
        for (String k : keys) {
            Object v = m.get(k);
            if (v != null && !String.valueOf(v).isBlank()) {
                return v;
            }
        }
        return null;
    }

    /**
     * @param namePrefix 模糊前缀；null/blank 时按 [a-z0-9_-] 扫描合并（Nacos blur 全量限制的绕行）
     */
    public List<Map<String, Object>> listAgents(String namespaceId, String namePrefix) throws Exception {
        Map<String, Map<String, Object>> byName = new LinkedHashMap<>();
        if (namePrefix != null && !namePrefix.isBlank()) {
            collectAgents(namespaceId, namePrefix, byName);
        } else {
            for (String p : SCAN_PREFIXES) {
                collectAgents(namespaceId, p, byName);
            }
        }
        return new ArrayList<>(byName.values());
    }

    private void collectAgents(String namespaceId, String namePrefix, Map<String, Map<String, Object>> out)
            throws Exception {
        JsonNode root = get("/v3/admin/ai/a2a/list?pageNo=1&pageSize=100&namespaceId="
                + enc(namespaceId) + "&search=blur&agentName=" + enc(namePrefix));
        JsonNode items = root.path("data").path("pageItems");
        if (items.isArray()) {
            for (JsonNode n : items) {
                Map<String, Object> m = toMap(n);
                Object name = m.get("name");
                if (name != null) {
                    out.putIfAbsent(String.valueOf(name), m);
                }
            }
        }
    }

    private static final List<String> SCAN_PREFIXES = List.of(
            "a", "b", "c", "d", "e", "f", "g", "h", "i", "j", "k", "l", "m",
            "n", "o", "p", "q", "r", "s", "t", "u", "v", "w", "x", "y", "z",
            "A", "B", "C", "D", "E", "F", "G", "H", "I", "J", "K", "L", "M",
            "N", "O", "P", "Q", "R", "S", "T", "U", "V", "W", "X", "Y", "Z",
            "0", "1", "2", "3", "4", "5", "6", "7", "8", "9");

    public Optional<Map<String, Object>> getAgent(String namespaceId, String agentName, String version)
            throws Exception {
        StringBuilder q = new StringBuilder("/v3/admin/ai/a2a?namespaceId=").append(enc(namespaceId))
                .append("&agentName=").append(enc(agentName));
        if (version != null && !version.isBlank()) {
            q.append("&version=").append(enc(version));
        }
        JsonNode root = get(q.toString());
        JsonNode data = root.path("data");
        if (data.isMissingNode() || data.isNull()) {
            return Optional.empty();
        }
        return Optional.of(toMap(data));
    }

    /** 从 toolSpec / toolSpecification 提取已启用工具名（逗号串）。空则返回 null。 */
    public static String enabledToolsFrom(Object toolSpec) {
        if (toolSpec == null) {
            return null;
        }
        JsonNode node;
        try {
            ObjectMapper m = new ObjectMapper();
            node = toolSpec instanceof JsonNode j ? j : m.valueToTree(toolSpec);
        } catch (Exception e) {
            return null;
        }
        if (node == null || node.isNull()) {
            return null;
        }
        List<String> names = new ArrayList<>();
        JsonNode meta = node.path("toolsMeta");
        JsonNode tools = node.path("tools");
        if (!tools.isArray()) {
            tools = node.path("toolList");
        }
        if (tools.isArray()) {
            for (JsonNode t : tools) {
                String n = t.path("name").asText(null);
                if (n == null || n.isBlank()) {
                    n = t.path("toolName").asText(null);
                }
                if (n == null || n.isBlank()) {
                    continue;
                }
                // 优先 toolsMeta[name].enabled（Nacos McpToolMeta）；其次 tools[].enabled；缺省 true
                Boolean enabled = null;
                if (meta.isObject() && meta.has(n)) {
                    JsonNode me = meta.path(n).path("enabled");
                    if (me.isBoolean()) {
                        enabled = me.asBoolean();
                    } else if (me.isTextual() && !me.asText().isBlank()) {
                        enabled = !"false".equalsIgnoreCase(me.asText());
                    }
                }
                if (enabled == null) {
                    JsonNode en = t.path("enabled");
                    if (en.isBoolean()) {
                        enabled = en.asBoolean();
                    } else if (en.isTextual() && !en.asText().isBlank()) {
                        enabled = !"false".equalsIgnoreCase(en.asText());
                    }
                }
                if (enabled == null || enabled) {
                    names.add(n.trim());
                }
            }
        }
        // toolSpec 有时是 name→enabled map
        if (names.isEmpty() && node.isObject() && !node.has("tools") && !node.has("toolList")) {
            node.fields().forEachRemaining(e -> {
                if (e.getValue().isBoolean()) {
                    if (e.getValue().asBoolean()) {
                        names.add(e.getKey());
                    }
                } else if (e.getValue().isObject()) {
                    JsonNode en = e.getValue().path("enabled");
                    if (!en.isBoolean() || en.asBoolean()) {
                        names.add(e.getKey());
                    }
                }
            });
        }
        return names.isEmpty() ? null : String.join(",", names);
    }

    /** remote/local config 里提取 http(s) URL；或从 backendEndpoints 拼 http://host:port/path。 */
    public static String urlFrom(Map<String, Object> detail) {
        if (detail == null) {
            return null;
        }
        for (String key : List.of("remoteServerConfig", "frontendEndpoints", "endpointSpecification")) {
            Object v = detail.get(key);
            if (v == null) {
                continue;
            }
            try {
                JsonNode n = v instanceof JsonNode j ? j : new ObjectMapper().valueToTree(v);
                String found = findHttp(n);
                if (found != null) {
                    return found;
                }
            } catch (Exception ignored) {
                // fall through
            }
        }
        // backendEndpoints: [{address, port, path}]
        Object eps = detail.get("backendEndpoints");
        if (eps != null) {
            try {
                JsonNode arr = eps instanceof JsonNode j ? j : new ObjectMapper().valueToTree(eps);
                JsonNode node = arr.isArray() && arr.size() > 0 ? arr.get(0) : arr;
                String host = firstText(node, "address", "host", "ip");
                int port = node.path("port").asInt(0);
                String path = firstText(node, "path");
                if (host != null && !host.isBlank() && port > 0) {
                    if (path == null || path.isBlank()) {
                        path = "/mcp";
                    }
                    if (!path.startsWith("/")) {
                        path = "/" + path;
                    }
                    return "http://" + host + ":" + port + path;
                }
            } catch (Exception ignored) {
                // fall through
            }
        }
        return null;
    }

    private static String firstText(JsonNode n, String... fields) {
        if (n == null) {
            return null;
        }
        for (String f : fields) {
            String v = n.path(f).asText(null);
            if (v != null && !v.isBlank() && !"null".equals(v)) {
                return v;
            }
        }
        return null;
    }

    private static String findHttp(JsonNode n) {
        if (n == null) {
            return null;
        }
        if (n.isTextual() && n.asText().startsWith("http")) {
            return n.asText();
        }
        if (n.isObject() || n.isArray()) {
            var it = n.elements();
            while (it.hasNext()) {
                String f = findHttp(it.next());
                if (f != null) {
                    return f;
                }
            }
        }
        return null;
    }

    JsonNode get(String pathAndQuery) throws Exception {
        String body = getString(pathAndQuery);
        JsonNode root = mapper.readTree(body);
        if (root.isObject() && root.has("code")) {
            int code = root.path("code").asInt(0);
            if (code != 0) {
                throw new IllegalStateException("Nacos AI API code=" + code
                        + " message=" + root.path("message").asText());
            }
        }
        return root;
    }

    /** 原文 GET（skill/prompt download 等非 JSON 响应）。 */
    String getString(String pathAndQuery) throws Exception {
        String url = properties.baseUrl() + pathAndQuery;
        HttpRequest.Builder b = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(8))
                .GET();
        if (properties.username() != null && !properties.username().isBlank()) {
            String token = loginToken(properties.username(), properties.password());
            if (token != null) {
                b.header("accesstoken", token);
            }
            b.header("username", properties.username());
            if (properties.password() != null) {
                b.header("password", properties.password());
            }
        }
        // Nacos 3.x Admin API：serverIdentity（等同运维鉴权）优先于用户登录
        if (properties.identityKey() != null && !properties.identityKey().isBlank()) {
            b.header(properties.identityKey(), properties.identityValue() == null ? "" : properties.identityValue());
        }
        HttpResponse<String> resp = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() >= 300) {
            throw new IllegalStateException("Nacos AI API HTTP " + resp.statusCode() + " url=" + pathAndQuery);
        }
        return resp.body();
    }

    /** 简化登录：部分部署用 accessToken；失败则匿名（dev）。 */
    String loginToken(String username, String password) {
        try {
            String body = "username=" + enc(username) + "&password=" + enc(password == null ? "" : password);
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(properties.baseUrl() + "/v1/auth/users/login"))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .timeout(Duration.ofSeconds(5))
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            JsonNode root = mapper.readTree(resp.body());
            return root.path("data").path("accessToken").asText(null);
        } catch (Exception e) {
            log.debug("nacos login skipped: {}", e.toString());
            return null;
        }
    }

    private Map<String, Object> toMap(JsonNode n) {
        return mapper.convertValue(n, LinkedHashMap.class);
    }

    private static String enc(String s) {
        return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
    }
}
