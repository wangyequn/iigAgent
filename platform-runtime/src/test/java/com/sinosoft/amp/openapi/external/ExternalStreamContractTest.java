package com.sinosoft.amp.openapi.external;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ExternalStreamContractTest {

    @Test
    void streamEndpointConventionIsDocumented() {
        // 外部约定：POST {baseUrl}/api/v1/messages:stream → text/event-stream
        String path = "/api/v1/messages:stream";
        assertTrue(path.endsWith("messages:stream"));
        assertTrue(path.contains("api/v1"));
    }
}
