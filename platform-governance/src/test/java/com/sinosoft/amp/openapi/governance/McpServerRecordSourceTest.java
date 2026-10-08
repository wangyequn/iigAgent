package com.sinosoft.amp.openapi.governance;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpServerRecordSourceTest {

    @Test
    void defaultsToLocalSource() {
        McpServerRecord r = new McpServerRecord(
                "acme", "m1", "stdio", "npx", "x", null, "t1", "REGISTERED");
        assertEquals(McpServerRecord.SOURCE_LOCAL, r.source());
        assertFalse(r.fromNacos());
    }

    @Test
    void blankSourceBecomesLocal() {
        McpServerRecord r = new McpServerRecord(
                "acme", "m1", "stdio", "npx", "x", null, "t1", "REGISTERED", " ");
        assertEquals(McpServerRecord.SOURCE_LOCAL, r.source());
    }

    @Test
    void nacosSourceFlag() {
        McpServerRecord r = new McpServerRecord(
                "acme", "m1", "sse", null, null, "http://x", "t1", "REGISTERED", McpServerRecord.SOURCE_NACOS);
        assertTrue(r.fromNacos());
    }
}
