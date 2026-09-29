package com.sinosoft.amp.openapi.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelCredentialServiceTest {

    @Test
    void providerOfParsesModelId() {
        assertEquals("deepseek", ModelCredentialService.providerOf("deepseek:deepseek-flash"));
        assertEquals("dashscope", ModelCredentialService.providerOf("DASHSCOPE:qwen-plus"));
        assertEquals("openai", ModelCredentialService.providerOf("openai:gpt-4o"));
    }

    @Test
    void maskHidesSecret() {
        assertEquals("", ModelCredentialService.mask(null));
        assertEquals("****", ModelCredentialService.mask("short"));
        String masked = ModelCredentialService.mask("sk-abcdefgh12345678");
        assertTrue(masked.endsWith("5678"));
        assertTrue(masked.startsWith("****"));
        assertEquals(8, masked.length());
    }
}
