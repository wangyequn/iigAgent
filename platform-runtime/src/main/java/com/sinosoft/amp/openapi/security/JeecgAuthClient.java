package com.sinosoft.amp.openapi.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

/**
 * pc_platform 客户端（模式 B）。
 * <ul>
 *   <li>{@link #exchangeCode}：code + client_id/secret → Jeecg JWT</li>
 *   <li>{@link #loginByToken}：认票 + 取用户名</li>
 *   <li>{@link #loadPerms}：getUserPermissionByToken → auth 权限码</li>
 * </ul>
 */
public class JeecgAuthClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final JeecgAuthProperties props;
    private final RestClient rest;

    public JeecgAuthClient(JeecgAuthProperties props) {
        this.props = props;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3000);
        factory.setReadTimeout(5000);
        String base = props.baseUrl() == null ? "" : props.baseUrl().trim();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        this.rest = RestClient.builder()
                .baseUrl(base.isEmpty() ? "http://127.0.0.1" : base)
                .requestFactory(factory)
                .build();
    }

    /** 仅解码 payload（username/clientId），生产必须再 {@link #loginByToken}。 */
    public static Optional<JsonNode> decodePayload(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        try {
            String[] parts = token.split("\\.");
            if (parts.length < 2) {
                return Optional.empty();
            }
            byte[] raw = Base64.getUrlDecoder().decode(parts[1]);
            return Optional.of(MAPPER.readTree(raw));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    public static String claimText(JsonNode payload, String... fields) {
        if (payload == null) {
            return null;
        }
        for (String f : fields) {
            if (payload.hasNonNull(f) && payload.get(f).isValueNode()) {
                String v = payload.get(f).asText();
                if (v != null && !v.isBlank()) {
                    return v;
                }
            }
        }
        return null;
    }


    /** Read HTTP body as UTF-8 text; tolerates application/octet-stream etc. */
    private static String bodyAsText(org.springframework.http.ResponseEntity<byte[]> entity) {
        byte[] bytes = entity == null ? null : entity.getBody();
        return bytes == null ? "" : new String(bytes, StandardCharsets.UTF_8);
    }

    /**
     * Mode B：getTokenByCode（code / client_id / client_secret）→ Result.result = accessToken 字符串。
     */
    public String exchangeCode(String code, String clientId, String clientSecret) {
        requireRemote();
        String cid = clientId == null || clientId.isBlank() ? props.clientId() : clientId;
        String secret = clientSecret == null || clientSecret.isBlank() ? props.clientSecret() : clientSecret;
        String uri = props.getTokenPath()
                + "?code=" + url(code)
                + "&client_id=" + url(cid)
                + "&client_secret=" + url(secret);
        String body = bodyAsText(rest.get()
                .uri(uri)
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .onStatus(s -> s.isError(), (req, res) -> {
                    try {
                        String err = new String(res.getBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                        throw new IllegalStateException("getTokenByCode HTTP " + res.getStatusCode().value() + " " + err);
                    } catch (java.io.IOException io) {
                        throw new IllegalStateException("getTokenByCode HTTP " + res.getStatusCode().value());
                    }
                })
                .toEntity(byte[].class));
        JsonNode node = parse(body);
        if (node == null) {
            throw new IllegalStateException("getTokenByCode: empty or invalid response");
        }
        if (node.has("success") && !node.get("success").asBoolean(true)) {
            String msg = node.hasNonNull("message") ? node.get("message").asText() : "token exchange failed";
            throw new IllegalStateException(msg);
        }
        if (node.hasNonNull("result") && node.get("result").isTextual()) {
            String token = node.get("result").asText();
            return token.isBlank() ? null : token;
        }
        if (node.hasNonNull("obj") && node.get("obj").isTextual()) {
            String token = node.get("obj").asText();
            return token.isBlank() ? null : token;
        }
        return null;
    }

    /**
     * 认票：POST /sys/loginByToken {"token":"..."}（pc_platform 实测路径）。
     * 成功 Result.result = {token, userInfo}；失败 success=false。
     */
    public Optional<JeecgIdentity> loginByToken(String token) {
        requireRemote();
        String body = bodyAsText(rest.post()
                .uri(props.loginByTokenPath())
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .body("{\"token\":\"" + escape(token) + "\"}")
                .retrieve()
                .toEntity(byte[].class));
        JsonNode node = parse(body);
        if (node == null) {
            return Optional.empty();
        }
        if (node.has("success") && !node.get("success").asBoolean(true)) {
            return Optional.empty();
        }
        JsonNode result = unwrap(node);
        JsonNode user = result;
        if (result != null && result.has("userInfo") && result.get("userInfo").isObject()) {
            user = result.get("userInfo");
        }
        String username = claimText(user, "username", "user_name", "account");
        if (username == null) {
            username = decodePayload(token).map(p -> claimText(p, "username", "sub")).orElse(null);
        }
        if (username == null) {
            return Optional.empty();
        }
        String clientId = claimText(result, "clientId", "client_id");
        if (clientId == null) {
            clientId = decodePayload(token).map(p -> claimText(p, "clientId", "client_id")).orElse(props.clientId());
        }
        Set<String> roles = new LinkedHashSet<>(extractRoles(user));
        // pc_platform：administratorsId 非空 / userIdentity=1 表示平台管理员
        if (isPlatformAdministrator(user) && !roles.contains("admin")) {
            roles.add("admin");
        }
        return Optional.of(new JeecgIdentity(username, clientId, Set.of(), roles));
    }

    static boolean isPlatformAdministrator(JsonNode user) {
        if (user == null) {
            return false;
        }
        String adminId = claimText(user, "administratorsId", "administrators_id", "administratorId");
        if (adminId != null && !adminId.isBlank() && !"0".equals(adminId) && !"null".equalsIgnoreCase(adminId)) {
            return true;
        }
        String identity = claimText(user, "userIdentity", "user_identity", "identity");
        return "1".equals(identity);
    }

    /** 权限码：GET getUserPermissionByToken，取 result.auth[]。 */
    public Set<String> loadPerms(String token) {
        requireRemote();
        String body = bodyAsText(rest.get()
                .uri(props.permissionPath())
                .header(props.tokenHeader(), token)
                .header("X-Access-Token", token)
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .toEntity(byte[].class));
        JsonNode node = parse(body);
        if (node == null) {
            return Set.of();
        }
        JsonNode result = node.hasNonNull("result") ? node.get("result") : node;
        Set<String> perms = new LinkedHashSet<>();
        collectPermActions(perms, result, "auth");
        // pc_platform（江苏）实测 auth 可能为空、权限在 allAuth[].action
        if (perms.isEmpty()) {
            collectPermActions(perms, result, "allAuth");
        }
        return JeecgIdentity.normalize(perms);
    }

    private static void collectPermActions(Set<String> perms, JsonNode result, String field) {
        if (result == null || !result.hasNonNull(field) || !result.get(field).isArray()) {
            return;
        }
        for (JsonNode a : result.get(field)) {
            if (a.isTextual()) {
                perms.add(a.asText());
            } else if (a.isObject()) {
                String code = claimText(a, "action", "code", "perms", "permission");
                if (code != null) {
                    perms.add(code);
                }
            }
        }
    }

    /** 认票 + 权限，组装完整身份。权限接口失败不导致整单 401。 */
    public Optional<JeecgIdentity> authenticate(String token) {
        try {
            return loginByToken(token).map(id -> {
                Set<String> perms;
                try {
                    perms = loadPerms(token);
                } catch (Exception e) {
                    perms = Set.of();
                }
                return new JeecgIdentity(id.username(), id.clientId(), perms, id.roles());
            });
        } catch (Exception e) {
            // 认票接口异常时退回 JWT payload（与 /exchange 兜底一致），避免整链 401
            return decodePayload(token).map(p -> {
                String username = claimText(p, "username", "user_name", "account", "sub");
                String clientId = claimText(p, "clientId", "client_id");
                if (username == null) {
                    return null;
                }
                return new JeecgIdentity(
                        username,
                        clientId == null ? props.clientId() : clientId,
                        Set.of(),
                        Set.of());
            }).filter(id -> id != null);
        }
    }

    private void requireRemote() {
        if (!props.remote()) {
            throw new IllegalStateException("amp.jeecg.base-url is required");
        }
    }

    private static JsonNode parse(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readTree(body);
        } catch (Exception e) {
            return null;
        }
    }

    private static JsonNode unwrap(JsonNode node) {
        if (node == null) {
            return null;
        }
        if (node.has("result") && node.get("result").isObject()) {
            return node.get("result");
        }
        if (node.has("obj") && node.get("obj").isObject()) {
            return node.get("obj");
        }
        return node;
    }

    private static Set<String> extractRoles(JsonNode user) {
        Set<String> roles = new LinkedHashSet<>();
        if (user != null && user.has("roles") && user.get("roles").isArray()) {
            for (JsonNode n : user.get("roles")) {
                if (n.isTextual()) {
                    roles.add(n.asText());
                } else if (n.isObject()) {
                    String c = claimText(n, "roleCode", "code", "role");
                    if (c != null) {
                        roles.add(c);
                    }
                }
            }
        }
        return JeecgIdentity.normalize(roles);
    }

    private static String url(String s) {
        if (s == null) {
            return "";
        }
        return java.net.URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
