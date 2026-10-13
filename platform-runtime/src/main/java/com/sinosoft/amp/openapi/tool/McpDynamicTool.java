package com.sinosoft.amp.openapi.tool;

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP 工具适配器：用自建 MCP 客户端（可带 httpRequestCustomizer）注册为 Agent 工具，
 * 从而在每次调用时注入人轨用户票（Harness ToolsConfig 只能带静态头）。
 */
public class McpDynamicTool extends ToolBase {

    private static final Logger log = LoggerFactory.getLogger(McpDynamicTool.class);

    private final McpClientWrapper client;
    private final String mcpServer;
    private final String remoteToolName;

    public McpDynamicTool(McpClientWrapper client, String mcpServer, McpSchema.Tool tool) {
        super(ToolBase.builder()
                .name(tool.name())
                .description(tool.description() == null
                        ? ("MCP tool from " + mcpServer)
                        : tool.description())
                .inputSchema(schemaOf(tool))
                .readOnly(false)
                .concurrencySafe(true));
        this.client = client;
        this.mcpServer = mcpServer;
        this.remoteToolName = tool.name();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> schemaOf(McpSchema.Tool tool) {
        Object schema = tool.inputSchema();
        if (schema instanceof Map<?, ?> m) {
            return (Map<String, Object>) m;
        }
        Map<String, Object> fallback = new LinkedHashMap<>();
        fallback.put("type", "object");
        fallback.put("properties", Map.of());
        return fallback;
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        long t0 = System.currentTimeMillis();
        Map<String, Object> input = param.getInput() == null ? Map.of() : param.getInput();
        return client.callTool(remoteToolName, input)
                .map(result -> {
                    StringBuilder text = new StringBuilder();
                    if (result != null && result.content() != null) {
                        for (Object c : result.content()) {
                            if (c instanceof McpSchema.TextContent t) {
                                if (text.length() > 0) {
                                    text.append('\n');
                                }
                                text.append(t.text());
                            } else if (c != null) {
                                if (text.length() > 0) {
                                    text.append('\n');
                                }
                                text.append(String.valueOf(c));
                            }
                        }
                    }
                    if (text.length() == 0) {
                        text.append("{\"ok\":true,\"mcp\":\"").append(mcpServer)
                                .append("\",\"tool\":\"").append(remoteToolName).append("\"}");
                    }
                    com.sinosoft.amp.openapi.ops.ToolCallAuditor.record(
                            getName(), "TOOL_MCP_OK",
                            mcpServer + "#" + remoteToolName + " " + input,
                            200, System.currentTimeMillis() - t0);
                    return ToolResultBlock.text(text.toString());
                })
                .onErrorResume(e -> {
                    log.warn("mcp tool {}.{} failed: {}", mcpServer, remoteToolName, e.toString());
                    com.sinosoft.amp.openapi.ops.ToolCallAuditor.record(
                            getName(), "TOOL_MCP_FAIL",
                            mcpServer + "#" + remoteToolName + " " + e.getMessage(),
                            500, System.currentTimeMillis() - t0);
                    return Mono.just(ToolResultBlock.error(getName(),
                            "mcp tool failed: " + e.getMessage()));
                });
    }

    public String mcpServer() {
        return mcpServer;
    }

    public String remoteToolName() {
        return remoteToolName;
    }

    public McpClientWrapper client() {
        return client;
    }

    /** 按 enableTools 过滤后包装为 Agent 工具列表。 */
    public static List<ToolBase> wrapTools(
            McpClientWrapper client, String mcpServer, List<McpSchema.Tool> tools, List<String> enableTools) {
        List<ToolBase> out = new ArrayList<>();
        if (tools == null) {
            return out;
        }
        for (McpSchema.Tool t : tools) {
            if (t == null || t.name() == null) {
                continue;
            }
            if (enableTools != null && !enableTools.isEmpty()
                    && enableTools.stream().noneMatch(e -> e.equalsIgnoreCase(t.name()))) {
                continue;
            }
            out.add(new McpDynamicTool(client, mcpServer, t));
        }
        return out;
    }
}
