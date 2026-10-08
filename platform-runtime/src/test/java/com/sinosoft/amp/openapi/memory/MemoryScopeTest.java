package com.sinosoft.amp.openapi.memory;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MemoryScopeTest {

    @AfterEach
    void tearDown() {
        MemoryScope.clear();
    }

    @Test
    void bindAndRequire() {
        MemoryScope.bind("acme", "alice", "demo", "sess-1");
        MemoryScope.Scope s = MemoryScope.require();
        assertEquals("acme", s.tenantId());
        assertEquals("alice", s.userId());
        assertEquals("demo", s.agentId());
        assertEquals("sess-1", s.sessionId());
    }

    @Test
    void clearRemovesScope() {
        MemoryScope.bind("acme", "alice", "demo", "sess-1");
        MemoryScope.clear();
        assertNull(MemoryScope.get());
        assertThrows(IllegalStateException.class, MemoryScope::require);
    }

    @Test
    void mem0UserIdPrefixesTenant() {
        assertEquals("acme_alice", ScopedMem0LongTermMemory.mem0UserId("acme", "alice"));
        assertEquals("acme_anonymous", ScopedMem0LongTermMemory.mem0UserId("acme", null));
        assertEquals("acme_alice", ScopedMem0LongTermMemory.mem0UserId(null, "alice"));
    }

    @Test
    void mem0PropertiesNormalizeAndConfigured() {
        Mem0Properties p = new Mem0Properties(true, "http://127.0.0.1:8888/", "k",
                "self-hosted", true, true, 5, 30);
        assertEquals("http://127.0.0.1:8888", p.normalizedBaseUrl());
        assertEquals(true, p.isConfigured());
        assertEquals(true, p.isSelfHosted());
        Mem0Properties off = new Mem0Properties(false, null, null, null, null, null, null, null);
        assertEquals(false, off.isConfigured());
    }
}
