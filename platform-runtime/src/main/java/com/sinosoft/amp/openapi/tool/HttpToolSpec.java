package com.sinosoft.amp.openapi.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sinosoft.amp.openapi.catalog.CatalogException;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * HTTP 业务工具配置（business_tool.config JSON）。
 *
 * <pre>
 * {
 *   "url": "http://127.0.0.1:8080/api/orders",
 *   "method": "GET|POST|PUT|DELETE",
 *   "headers": {"X-Api-Key": "secret"},
 *   "readOnly": true,
 *   "parameters": {
 *     "type": "object",
 *     "properties": {"id": {"type": "string", "description": "记录 ID"}},
 *     "required": ["orderId"]
 *   }
 * }
 * </pre>
 */
public record HttpToolSpec(
        String name,
        String description,
        String url,
        String method,
        Map<String, String> headers,
        boolean readOnly,
        Map<String, Object> parameters) {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static HttpToolSpec from(BusinessToolRecord rec) {
        String config = rec.config() == null ? "" : rec.config().trim();
        if (config.isEmpty() || config.startsWith("{") == false) {
            throw new CatalogException("HTTP_TOOL_CONFIG_REQUIRED",
                    "HTTP tool " + rec.toolName() + " needs config JSON with url");
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> cfg = MAPPER.readValue(config, Map.class);
            String url = str(cfg.get("url"));
            if (url == null || url.isBlank()) {
                throw new CatalogException("HTTP_TOOL_URL_REQUIRED", "config.url is required for " + rec.toolName());
            }
            String method = str(cfg.get("method"));
            if (method == null || method.isBlank()) {
                method = "POST";
            }
            method = method.toUpperCase(Locale.ROOT);
            Map<String, String> headers = new LinkedHashMap<>();
            if (cfg.get("headers") instanceof Map<?, ?> hm) {
                for (Map.Entry<?, ?> e : hm.entrySet()) {
                    headers.put(String.valueOf(e.getKey()), e.getValue() == null ? "" : String.valueOf(e.getValue()));
                }
            }
            boolean readOnly = cfg.get("readOnly") instanceof Boolean b ? b : ("GET".equals(method) || "HEAD".equals(method));
            @SuppressWarnings("unchecked")
            Map<String, Object> parameters = cfg.get("parameters") instanceof Map
                    ? (Map<String, Object>) cfg.get("parameters")
                    : defaultSchema();
            return new HttpToolSpec(
                    rec.toolName(),
                    rec.description() == null ? rec.toolName() : rec.description(),
                    url,
                    method,
                    headers,
                    readOnly,
                    parameters);
        } catch (CatalogException e) {
            throw e;
        } catch (Exception e) {
            throw new CatalogException("HTTP_TOOL_CONFIG_INVALID", "invalid config for " + rec.toolName() + ": " + e.getMessage());
        }
    }

    private static Map<String, Object> defaultSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", Map.of());
        return schema;
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
