package com.sinosoft.amp.common.api;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class ApiErrorTest {

    @Test
    void ofCreatesError() {
        ApiError error = ApiError.of("SESSION_CONTEXT_INVALID", "missing session", "req-1");
        assertEquals("SESSION_CONTEXT_INVALID", error.code());
        assertNotNull(error.timestamp());
        assertEquals("req-1", error.requestId());
    }
}
