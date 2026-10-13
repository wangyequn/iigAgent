package com.sinosoft.amp.openapi.governance;

import com.sinosoft.amp.openapi.catalog.CatalogException;
import io.agentscope.core.tool.mcp.McpClientBuilder;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import io.agentscope.harness.agent.tools.McpServerConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * MCP 真实连通：探测 / 列工具 / 按登记构建 McpServerConfig。
 * 初始化超时默认 5s，失败不阻塞业务。
 */
@Service
public class McpConnectService {

    private static final Logger log = LoggerFactory.getLogger(McpConnectService.class);

    private final GovernanceService governance;

    public McpConnectService(GovernanceService governance) {
        this.governance = governance;
    }

    public McpServerConfig toConfig(McpServerRecord r, Duration initTimeout) {
        McpServerConfig mc = new McpServerConfig();
        mc.setTransport(r.transport());
        if (r.command() != null && !r.command().isBlank()) {
            mc.setCommand(r.command());
        }
        if (r.args() != null && !r.args().isBlank()) {
            mc.setArgs(new ArrayList<>(List.of(r.args().trim().split("\\s+"))));
        }
        if (r.url() != null && !r.url().isBlank()) {
            mc.setUrl(r.url());
        }
        List<String> tools = GovernanceService.splitTools(r.enableTools());
        if (!tools.isEmpty()) {
            mc.setEnableTools(new ArrayList<>(tools));
        } else if (requireExplicitTools()) {
            // 零信任：未显式列出工具则不暴露任何 MCP 工具
            throw new IllegalStateException("mcp " + r.name() + " has no enableTools; skip injection");
        }
        // 静态头（authMode=client）；user_token 由 buildClient 的 customizer 按请求注入
        Map<String, String> staticHeaders = r.headers();
        if (staticHeaders != null && !staticHeaders.isEmpty()) {
            mc.setHeaders(new LinkedHashMap<>(staticHeaders));
        }
        mc.setTimeout(Duration.ofSeconds(5));
        mc.setInitializationTimeout(initTimeout == null ? Duration.ofSeconds(5) : initTimeout);
        return mc;
    }

    /**
     * 探测连通性并列出工具。
     */
    public Map<String, Object> probe(String tenantId, String name) {
        McpServerRecord rec = governance.listMcp(tenantId).stream()
                .filter(m -> m.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new CatalogException("MCP_NOT_FOUND", "mcp server not found: " + name));
        return probeRecord(rec);
    }

    public Map<String, Object> probeRecord(McpServerRecord rec) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", rec.name());
        out.put("transport", rec.transport());
        out.put("url", rec.url());
        out.put("command", rec.command());
        long t0 = System.currentTimeMillis();
        McpClientWrapper client = null;
        try {
            client = buildClient(rec);
            client.initialize().block(Duration.ofSeconds(8));
            List<Map<String, Object>> tools = new ArrayList<>();
            List<io.modelcontextprotocol.spec.McpSchema.Tool> listed =
                    client.listTools().block(Duration.ofSeconds(8));
            if (listed != null) {
                for (io.modelcontextprotocol.spec.McpSchema.Tool t : listed) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("name", t.name());
                    m.put("description", t.description());
                    tools.add(m);
                }
            }
            out.put("status", "CONNECTED");
            out.put("toolCount", tools.size());
            out.put("tools", tools);
            out.put("durationMs", System.currentTimeMillis() - t0);
        } catch (Exception e) {
            out.put("status", "FAILED");
            out.put("error", e.getMessage() == null ? e.toString() : e.getMessage());
            out.put("durationMs", System.currentTimeMillis() - t0);
            log.warn("mcp probe failed name={} err={}", rec.name(), e.toString());
        } finally {
            if (client != null) {
                try {
                    client.close();
                } catch (Exception ignored) {
                }
            }
        }
        return out;
    }

    /**
     * 构建客户端（stdio / sse / streamable_http）。
     */
    McpClientWrapper buildClient(McpServerRecord rec) {
        String transport = rec.transport() == null ? "streamable_http" : rec.transport().toLowerCase(Locale.ROOT);
        McpClientBuilder b = McpClientBuilder.create(rec.name())
                .initializationTimeout(Duration.ofSeconds(5))
                .timeout(Duration.ofSeconds(5));
        switch (transport) {
            case "stdio" -> {
                List<String> args = rec.args() == null || rec.args().isBlank()
                        ? List.of()
                        : List.of(rec.args().trim().split("\\s+"));
                b = b.stdioTransport(rec.command(), args.toArray(new String[0]));
            }
            case "sse" -> b = b.sseTransport(rec.url());
            case "http", "streamable_http", "streamablehttp" -> b = b.streamableHttpTransport(rec.url());
            default -> throw new CatalogException("MCP_TRANSPORT_INVALID", "unsupported transport: " + transport);
        }
        Map<String, String> staticHeaders = rec.headers();
        if (staticHeaders != null) {
            staticHeaders.forEach(b::header);
        }
        // user_token：按请求注入人轨票（Agent 权限 ⊆ 用户权限）
        if (!"stdio".equals(transport) && (staticHeaders == null || !staticHeaders.containsKey("Authorization"))) {
            b = b.httpRequestCustomizer((reqBuilder, method, uri, body, ctx) -> {
                String userToken = com.sinosoft.amp.openapi.security.UserTokenHolder.resolveOrSingleActive();
                if (userToken != null && !userToken.isBlank()) {
                    reqBuilder.header("X-Access-Token", userToken);
                    reqBuilder.header("Authorization", "Bearer " + userToken);
                }
            });
        }
        return b.buildSync();
    }

    /**
     * 仅返回可注入 ToolsConfig 的已登记服务器（软失败由 Harness 打日志）。
     */
    /**
     * 默认 true：enableTools 为空的 MCP 不注入（避免注册即全量暴露）。
     * 可用 -Damp.mcp.require-explicit-tools=false 或 env AMP_MCP_REQUIRE_EXPLICIT_TOOLS=false 放开。
     */
    static boolean requireExplicitTools() {
        String p = System.getProperty("amp.mcp.require-explicit-tools");
        if (p == null || p.isBlank()) {
            p = System.getenv("AMP_MCP_REQUIRE_EXPLICIT_TOOLS");
        }
        return p == null || p.isBlank() || Boolean.parseBoolean(p);
    }

    public Map<String, McpServerConfig> buildToolsConfigMap(String tenantId) {
        Map<String, McpServerConfig> map = new LinkedHashMap<>();
        for (McpServerRecord r : governance.listMcp(tenantId)) {
            if (!McpServerRecord.STATUS_REGISTERED.equalsIgnoreCase(r.status())
                    && !"CONNECTED".equalsIgnoreCase(r.status())) {
                continue;
            }
            try {
                map.put(r.name(), toConfig(r, Duration.ofSeconds(4)));
            } catch (Exception e) {
                log.warn("skip mcp {} : {}", r.name(), e.toString());
            }
        }
        return map;
    }

    /**
     * 自建客户端并返回已初始化 wrapper + 允许的工具名（用于注册 McpDynamicTool）。
     * 客户端带 httpRequestCustomizer，每次调用注入当前用户票。
     */
    public List<LiveMcpServer> openLiveServers(String tenantId) {
        List<LiveMcpServer> out = new ArrayList<>();
        for (McpServerRecord r : governance.listMcp(tenantId)) {
            if (!McpServerRecord.STATUS_REGISTERED.equalsIgnoreCase(r.status())
                    && !"CONNECTED".equalsIgnoreCase(r.status())) {
                continue;
            }
            McpClientWrapper client = null;
            try {
                client = buildClient(r);
                client.initialize().block(Duration.ofSeconds(6));
                out.add(new LiveMcpServer(r.name(), client, GovernanceService.splitTools(r.enableTools())));
                log.info("mcp live server opened tenant={} name={}", tenantId, r.name());
            } catch (Exception e) {
                log.warn("open mcp live server failed name={}: {}", r.name(), e.toString());
                if (client != null) {
                    try {
                        client.close();
                    } catch (Exception ignored) {
                    }
                }
            }
        }
        return out;
    }

    /** 已打开的 MCP 服务（含客户端与允许工具列表）。 */
    public record LiveMcpServer(String name, McpClientWrapper client, List<String> enableTools) {
    }
}
