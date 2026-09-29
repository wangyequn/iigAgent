package com.sinosoft.amp.openapi.tool;

import com.sinosoft.amp.openapi.sandbox.DockerSandboxService;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;

/**
 * 不可信命令沙箱工具（危险写/执行，默认 ASK）。
 * 仅在 amp.sandbox.enabled=true 时注册到 Agent。
 */
public class SandboxToolset {

    private final DockerSandboxService sandbox;
    private final String tenantId;

    public SandboxToolset(DockerSandboxService sandbox, String tenantId) {
        this.sandbox = sandbox;
        this.tenantId = tenantId;
    }

    @Tool(
            name = "sandbox_exec",
            description = "在 Docker 沙箱中执行不可信 shell 命令（无网络、只读、降权）。危险操作，需人工确认。",
            readOnly = false,
            concurrencySafe = false)
    public String sandboxExec(
            @ToolParam(name = "command", required = true, description = "shell 命令")
            Object command) {
        String cmd = command == null ? "" : String.valueOf(command);
        try {
            DockerSandboxService.ExecResult r = sandbox.exec(tenantId, "sandbox", "tool", "tool", cmd);
            return "{\"success\":" + r.success()
                    + ",\"exitCode\":" + r.exitCode()
                    + ",\"isolationScope\":\"" + r.isolationScope() + "\""
                    + ",\"durationMs\":" + r.durationMs()
                    + ",\"stdout\":\"" + escape(r.stdout()) + "\""
                    + ",\"stderr\":\"" + escape(r.stderr()) + "\"}";
        } catch (RuntimeException e) {
            return "{\"success\":false,\"error\":\"" + escape(e.getMessage()) + "\"}";
        }
    }

    private static String escape(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "").replace("\t", "\\t");
    }
}
