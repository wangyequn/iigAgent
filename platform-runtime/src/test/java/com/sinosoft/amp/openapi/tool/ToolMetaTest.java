package com.sinosoft.amp.openapi.tool;

import com.sinosoft.amp.openapi.catalog.CatalogException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolMetaTest {

    @Test
    void defaultsUserTokenAndMediumRiskForWrite() {
        ToolMeta meta = ToolMeta.fromConfig("{\"url\":\"http://x\"}", "POST");
        assertEquals(ToolMeta.AUTH_USER_TOKEN, meta.authMode());
        assertEquals(ToolMeta.RISK_MEDIUM, meta.risk());
    }

    @Test
    void getDefaultsToLowRisk() {
        ToolMeta meta = ToolMeta.fromConfig("{}", "GET");
        assertEquals(ToolMeta.RISK_LOW, meta.risk());
        assertTrue(meta.readOnly());
    }

    @Test
    void forbiddenRejected() {
        ToolMeta meta = new ToolMeta("forbidden", "", "user_token", "", true);
        assertThrows(CatalogException.class, () -> meta.validateForRegistration("t", "GET"));
    }

    @Test
    void clientWriteRejected() {
        ToolMeta meta = new ToolMeta("low", "", "client", "", false);
        assertThrows(CatalogException.class, () -> meta.validateForRegistration("t", "DELETE"));
    }

    @Test
    void mergeKeepsUrlAndWritesRisk() {
        String out = ToolMeta.mergeIntoConfig(
                "{\"url\":\"http://x\",\"method\":\"GET\"}",
                new ToolMeta("high", "order:del", "user_token", "订单", false));
        assertTrue(out.contains("\"risk\":\"high\""));
        assertTrue(out.contains("order:del"));
        assertTrue(out.contains("http://x"));
    }
}
