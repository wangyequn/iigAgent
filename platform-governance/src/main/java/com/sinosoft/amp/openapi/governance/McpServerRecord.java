package com.sinosoft.amp.openapi.governance;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * MCP 登记。source=local 为平台种子/手工；source=nacos 为 AI Registry 单向发现导入。
 * 同名冲突时 local 优先，不被 nacos 覆盖（sync-direction=nacos-to-local）。
 * headers_json 为 JSON 对象字符串（客户端凭证）；user_token 由 MCP 客户端按请求注入。
 */
public record McpServerRecord(
        String tenantId,
        String name,
        String transport,
        String command,
        String args,
        String url,
        String enableTools,
        String status,
        String source,
        String headersJson
) {
    public static final String STATUS_REGISTERED = "REGISTERED";
    public static final String SOURCE_LOCAL = "local";
    public static final String SOURCE_NACOS = "nacos";

    public McpServerRecord {
        if (source == null || source.isBlank()) {
            source = SOURCE_LOCAL;
        }
        if (headersJson == null) {
            headersJson = "";
        }
    }

    /** 兼容旧构造：无静态头。 */
    public McpServerRecord(
            String tenantId, String name, String transport, String command,
            String args, String url, String enableTools, String status, String source) {
        this(tenantId, name, transport, command, args, url, enableTools, status, source, "");
    }

    /** 兼容旧构造：默认 source=local。 */
    public McpServerRecord(
            String tenantId, String name, String transport, String command,
            String args, String url, String enableTools, String status) {
        this(tenantId, name, transport, command, args, url, enableTools, status, SOURCE_LOCAL, "");
    }

    public boolean fromNacos() {
        return SOURCE_NACOS.equals(source);
    }

    /** 解析 headers_json；空/非法返回空 Map。 */
    public Map<String, String> headers() {
        if (headersJson == null || headersJson.isBlank()) {
            return Collections.emptyMap();
        }
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            @SuppressWarnings("unchecked")
            Map<String, Object> raw = mapper.readValue(headersJson, Map.class);
            Map<String, String> out = new LinkedHashMap<>();
            if (raw != null) {
                for (Map.Entry<String, Object> e : raw.entrySet()) {
                    if (e.getKey() != null) {
                        out.put(e.getKey(), e.getValue() == null ? "" : String.valueOf(e.getValue()));
                    }
                }
            }
            return out;
        } catch (Exception e) {
            return Collections.emptyMap();
        }
    }

    public static String headersToJson(Map<String, String> headers) {
        if (headers == null || headers.isEmpty()) {
            return "";
        }
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(headers);
        } catch (Exception e) {
            return "";
        }
    }
}
