package com.sinosoft.amp.openapi.web;

import com.sinosoft.amp.common.api.ApiError;
import com.sinosoft.amp.openapi.catalog.CatalogException;
import com.sinosoft.amp.openapi.governance.GovernanceService;
import com.sinosoft.amp.openapi.governance.McpConnectService;
import com.sinosoft.amp.openapi.governance.McpServerRecord;
import com.sinosoft.amp.openapi.security.AuthContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * MCP 连通性管理：探测 / 列工具（注册/列表在 AdminGovernanceController）。
 */
@RestController
@RequestMapping("/api/v1/admin/mcp-servers")
public class AdminMcpController {

    private final McpConnectService mcpConnect;

    public AdminMcpController(GovernanceService governance, McpConnectService mcpConnect) {
        this.governance = governance;
        this.mcpConnect = mcpConnect;
    }

    private final GovernanceService governance;

    /** 探测连通并列出工具（真实 MCP listTools）。 */
    @PostMapping("/{name}/test")
    public Map<String, Object> test(@PathVariable String name) {
        String tenant = AuthContext.requireTenantId();
        AuthContext.requireAgentIdOrDefault(null);
        return mcpConnect.probe(tenant, name);
    }

    @GetMapping("/{name}/tools")
    public Map<String, Object> tools(@PathVariable String name) {
        return test(name);
    }

    @ExceptionHandler(CatalogException.class)
    public ResponseEntity<ApiError> handle(CatalogException e) {
        HttpStatus status = switch (e.code()) {
            case "MCP_NOT_FOUND" -> HttpStatus.NOT_FOUND;
            default -> HttpStatus.BAD_REQUEST;
        };
        return ResponseEntity.status(status).body(ApiError.of(e.code(), e.getMessage(), requestId()));
    }

    private static String requestId() {
        ServletRequestAttributes attrs =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attrs != null && attrs.getRequest().getHeader("X-Request-Id") != null) {
            return attrs.getRequest().getHeader("X-Request-Id");
        }
        return UUID.randomUUID().toString();
    }
}
