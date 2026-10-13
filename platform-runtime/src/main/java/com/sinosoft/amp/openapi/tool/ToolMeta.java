package com.sinosoft.amp.openapi.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sinosoft.amp.openapi.catalog.CatalogException;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 外部工具安全元数据（business_tool.config 扩展字段）。
 *
 * <pre>
 * {
 *   "risk": "low|medium|high|forbidden",
 *   "permCode": "order:query",
 *   "authMode": "user_token|client|none",
 *   "ownerSystem": "订单系统",
 *   "readOnly": true
 * }
 * </pre>
 */
public record ToolMeta(
        String risk,
        String permCode,
        String authMode,
        String ownerSystem,
        boolean readOnly) {

    public static final String RISK_LOW = "low";
    public static final String RISK_MEDIUM = "medium";
    public static final String RISK_HIGH = "high";
    public static final String RISK_FORBIDDEN = "forbidden";

    public static final String AUTH_USER_TOKEN = "user_token";
    public static final String AUTH_CLIENT = "client";
    public static final String AUTH_NONE = "none";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public ToolMeta {
        risk = normalizeRisk(risk);
        permCode = permCode == null ? "" : permCode.trim();
        authMode = normalizeAuth(authMode);
        ownerSystem = ownerSystem == null ? "" : ownerSystem.trim();
    }

    public static String normalizeRisk(String risk) {
        if (risk == null || risk.isBlank()) {
            return RISK_MEDIUM;
        }
        return risk.trim().toLowerCase(Locale.ROOT);
    }

    public static String normalizeAuth(String authMode) {
        if (authMode == null || authMode.isBlank()) {
            return AUTH_USER_TOKEN;
        }
        return authMode.trim().toLowerCase(Locale.ROOT);
    }

    public boolean isForbidden() {
        return RISK_FORBIDDEN.equals(risk);
    }

    public boolean isHigh() {
        return RISK_HIGH.equals(risk);
    }

    /** 从 config JSON 解析；无字段时按 method 给出保守默认。 */
    public static ToolMeta fromConfig(String configJson, String method) {
        Map<String, Object> cfg = parseObject(configJson);
        String m = method == null ? "POST" : method.toUpperCase(Locale.ROOT);
        String risk = str(cfg.get("risk"));
        if (risk == null || risk.isBlank()) {
            risk = ("GET".equals(m) || "HEAD".equals(m)) ? RISK_LOW : RISK_MEDIUM;
        }
        String readOnlyRaw = str(cfg.get("readOnly"));
        boolean readOnly = readOnlyRaw == null
                ? ("GET".equals(m) || "HEAD".equals(m))
                : Boolean.parseBoolean(readOnlyRaw);
        return new ToolMeta(
                risk,
                str(cfg.get("permCode")),
                str(cfg.get("authMode")),
                str(cfg.get("ownerSystem")),
                readOnly);
    }

    /** 规范化写回 config，保证后续 fromConfig 能读到。 */
    public static String mergeIntoConfig(String configJson, ToolMeta meta) {
        Map<String, Object> cfg = parseObject(configJson);
        if (cfg.isEmpty()) {
            cfg = new LinkedHashMap<>();
        }
        cfg.put("risk", meta.risk());
        cfg.put("authMode", meta.authMode());
        cfg.put("readOnly", meta.readOnly());
        if (meta.permCode() != null && !meta.permCode().isBlank()) {
            cfg.put("permCode", meta.permCode());
        }
        if (meta.ownerSystem() != null && !meta.ownerSystem().isBlank()) {
            cfg.put("ownerSystem", meta.ownerSystem());
        }
        try {
            return MAPPER.writeValueAsString(cfg);
        } catch (Exception e) {
            throw new CatalogException("HTTP_TOOL_CONFIG_INVALID", "cannot serialize tool config: " + e.getMessage());
        }
    }

    /**
     * 注册门禁：
     * forbidden 拒绝；high + client + 非只读拒绝；client 写接口拒绝。
     */
    public void validateForRegistration(String toolName, String method) {
        if (isForbidden()) {
            throw new CatalogException("TOOL_RISK_FORBIDDEN",
                    "tool " + toolName + " risk=forbidden cannot be registered");
        }
        String m = method == null ? "POST" : method.toUpperCase(Locale.ROOT);
        boolean mutating = !("GET".equals(m) || "HEAD".equals(m));
        if (AUTH_CLIENT.equals(authMode) && mutating) {
            throw new CatalogException("TOOL_AUTH_CLIENT_WRITE_DENIED",
                    "tool " + toolName + " authMode=client cannot call write method " + m);
        }
        if (isHigh() && AUTH_CLIENT.equals(authMode)) {
            throw new CatalogException("TOOL_AUTH_CLIENT_HIGH_RISK_DENIED",
                    "tool " + toolName + " risk=high cannot use authMode=client");
        }
    }

    private static Map<String, Object> parseObject(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> cfg = MAPPER.readValue(json, Map.class);
            return cfg == null ? Map.of() : cfg;
        } catch (Exception e) {
            throw new CatalogException("HTTP_TOOL_CONFIG_INVALID", "invalid tool config JSON: " + e.getMessage());
        }
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
