package com.sinosoft.amp.openapi.tool;

import io.agentscope.core.tool.ToolBase;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class McpDynamicToolTest {

    private static McpSchema.Tool tool(String name) {
        McpSchema.Tool t = mock(McpSchema.Tool.class);
        when(t.name()).thenReturn(name);
        when(t.description()).thenReturn(name + " desc");
        when(t.inputSchema()).thenReturn((McpSchema.JsonSchema) null);
        return t;
    }

    @Test
    void wrapFiltersByEnableTools() {
        List<ToolBase> wrapped = McpDynamicTool.wrapTools(
                null, "demo-mcp", List.of(tool("echo_time"), tool("delete_all")), List.of("echo_time"));
        assertEquals(1, wrapped.size());
        assertEquals("echo_time", wrapped.get(0).getName());
        assertTrue(wrapped.get(0) instanceof McpDynamicTool);
    }

    @Test
    void wrapAllowsAllWhenEnableListEmpty() {
        List<ToolBase> wrapped = McpDynamicTool.wrapTools(
                null, "m", List.of(tool("t1")), List.of());
        assertEquals(1, wrapped.size());
    }
}
