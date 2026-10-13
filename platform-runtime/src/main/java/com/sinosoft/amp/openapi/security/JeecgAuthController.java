package com.sinosoft.amp.openapi.security;

import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * pc_platform 模式 B：code 换票 / 当前用户 / 权限。
 * 与平台同网关同域，前端可先调 getCode 再走本接口 exchange。
 */
@RestController
@RequestMapping("/api/v1/auth/jeecg")
public class JeecgAuthController {

    private final JeecgAuthProperties props;
    private final JeecgAuthClient client;

    public JeecgAuthController(JeecgAuthProperties props, JeecgAuthClient client) {
        this.props = props;
        this.client = client;
    }

    /**
     * OAuth2 授权入口（标准链路第 2 步）：
     * 应用未登录 → 本接口 → 302 平台前端 {@code /user/authorize?redirect_uri&client_id}
     * 平台 getCode 后回跳 {@code redirect_uri?token=code}，再由 /exchange 换票。
     */
    @GetMapping("/authorize")
    public void authorize(
            @RequestParam(value = "redirect_uri", required = false) String redirectUri,
            @RequestParam(value = "client_id", required = false) String clientId,
            HttpServletResponse response) throws IOException {
        String callback = (redirectUri == null || redirectUri.isBlank())
                ? "http://localhost:5173/oauth2/callback"
                : redirectUri.trim();
        String cid = (clientId == null || clientId.isBlank()) ? props.clientId() : clientId.trim();
        String portal = props.portalUrl() == null ? "http://127.0.0.1:3200" : props.portalUrl().trim();
        if (portal.endsWith("/")) {
            portal = portal.substring(0, portal.length() - 1);
        }
        String target = portal + "/user/authorize"
                + "?redirect_uri=" + URLEncoder.encode(callback, StandardCharsets.UTF_8)
                + "&client_id=" + URLEncoder.encode(cid, StandardCharsets.UTF_8);
        response.sendRedirect(target);
    }

    /** 授权起点（同网关相对路径；前端可用已登录态调 pc_platform getCode）。 */
    @GetMapping("/config")
    public Map<String, Object> config() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", props.enabled());
        m.put("clientId", props.clientId());
        m.put("tokenHeader", props.tokenHeader());
        m.put("getCodePath", props.getCodePath());
        m.put("getTokenPath", props.getTokenPath());
        m.put("baseUrl", props.baseUrl());
        return m;
    }

    /** 代理 pc_platform 登录（避免前端跨域）。 */
    @PostMapping("/login")
    public Map<String, Object> login(@RequestBody LoginRequest body) {
        if (!props.remote()) {
            return Map.of("success", false, "message", "amp.jeecg.base-url not set");
        }
        try {
            SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
            factory.setConnectTimeout(3000);
            factory.setReadTimeout(5000);
            RestClient rest = RestClient.builder()
                    .baseUrl(props.baseUrl().endsWith("/") ? props.baseUrl().substring(0, props.baseUrl().length() - 1) : props.baseUrl())
                    .requestFactory(factory)
                    .build();
            String json = rest.post()
                    .uri("/sys/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body("{\"username\":\"" + esc(body.username())
                            + "\",\"password\":\"" + esc(body.password())
                            + "\",\"captcha\":\"" + esc(body.captcha())
                            + "\",\"checkKey\":\"" + esc(body.checkKey())
                            + "\",\"clientId\":\"" + esc(body.clientId() == null || body.clientId().isBlank() ? props.clientId() : body.clientId()) + "\"}")
                    .retrieve()
                    .body(String.class);
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(json, Map.class);
            return parsed;
        } catch (Exception e) {
            return Map.of("success", false, "message", e.getMessage() == null ? "login failed" : e.getMessage());
        }
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /** 模式 B：授权码换 Jeecg JWT。 */
    @PostMapping("/exchange")
    public Map<String, Object> exchange(@RequestBody ExchangeRequest body) {
        try {
            String token = client.exchangeCode(body.code(), body.clientId(), body.clientSecret());
            if (token == null || token.isBlank()) {
                return Map.of("success", false, "message", "exchange failed: empty token");
            }
            Optional<JeecgIdentity> id = Optional.empty();
            try {
                id = client.loginByToken(token);
            } catch (Exception loginEx) {
                // 换票已成功，认票失败仍返回 token，避免 500 导致登录中断
                String loginMsg = loginEx.getMessage() == null ? "loginByToken failed" : loginEx.getMessage();
                String jwtUser = JeecgAuthClient.decodePayload(token)
                        .map(p -> JeecgAuthClient.claimText(p, "username", "user_name", "account", "sub"))
                        .orElse("");
                String jwtClient = JeecgAuthClient.decodePayload(token)
                        .map(p -> JeecgAuthClient.claimText(p, "clientId", "client_id"))
                        .orElse(props.clientId());
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("success", true);
                out.put("token", token);
                out.put("username", jwtUser == null ? "" : jwtUser);
                out.put("clientId", jwtClient == null ? props.clientId() : jwtClient);
                out.put("warn", loginMsg);
                return out;
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("success", true);
            out.put("token", token);
            out.put("username", id.map(JeecgIdentity::username).orElse(""));
            out.put("clientId", id.map(JeecgIdentity::clientId).orElse(props.clientId()));
            return out;
        } catch (Exception e) {
            String msg = e.getMessage() == null ? "exchange failed" : e.getMessage();
            return Map.of("success", false, "message", msg);
        }
    }

    /** 当前登录用户（认票 + 权限码）。 */
    @GetMapping("/me")
    public Map<String, Object> me(@RequestHeader(value = JeecgAuthProperties.DEFAULT_HEADER, required = false) String token) {
        if (token == null || token.isBlank()) {
            return Map.of("success", false, "message", "missing X-Access-Token");
        }
        Optional<JeecgIdentity> id = client.authenticate(token.trim());
        if (id.isEmpty()) {
            return Map.of("success", false, "message", "invalid token");
        }
        JeecgIdentity jeecg = id.get();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("success", true);
        out.put("username", jeecg.username());
        out.put("clientId", jeecg.clientId());
        out.put("perms", jeecg.perms().stream().sorted().toList());
        out.put("roles", jeecg.roles().stream().sorted().toList());
        return out;
    }

    public record ExchangeRequest(String code, String clientId, String clientSecret) {
    }

    public record LoginRequest(String username, String password, String captcha, String checkKey, String clientId) {
    }
}
