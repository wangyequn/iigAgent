package com.sinosoft.amp.openapi.ops;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class ToolCallAuditorTest {

    @Test
    void recordWithoutServiceIsNoop() {
        ToolCallAuditor.bind(null);
        assertDoesNotThrow(() -> ToolCallAuditor.record("t", "TOOL_HTTP_OK", "{}", 200, 1L));
    }
}
