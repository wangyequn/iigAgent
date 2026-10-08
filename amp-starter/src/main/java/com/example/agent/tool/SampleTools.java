package com.example.agent.tool;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;

/**
 * 示例工具（非业务域）：只读 + 危险写，用于演示权限三态与 HITL。
 * 公卫/业务工具请照此模式替换为真实系统接口（HTTP/存储过程等）。
 */
public class SampleTools {

    @Tool(
            name = "safe_lookup",
            description = "只读查询示例。key 可选。",
            readOnly = true,
            concurrencySafe = true)
    public String safeLookup(
            @ToolParam(name = "key", required = false, description = "查询键")
            Object key) {
        String k = key == null ? "" : String.valueOf(key).trim();
        return "{\"ok\":true,\"key\":\"" + k + "\",\"value\":\"sample\"}";
    }

    @Tool(
            name = "dangerous_write",
            description = "危险写操作示例（执行前需人工确认）。target 必填。",
            readOnly = false,
            concurrencySafe = false)
    public String dangerousWrite(
            @ToolParam(name = "target", required = true, description = "写入目标")
            Object target,
            @ToolParam(name = "amount", required = false, description = "数量，默认1")
            Object amount) {
        if (target == null || String.valueOf(target).isBlank()) {
            return "{\"error\":\"target is required\"}";
        }
        return "{\"ok\":true,\"target\":\"" + String.valueOf(target) + "\",\"state\":\"CREATED\"}";
    }
}
