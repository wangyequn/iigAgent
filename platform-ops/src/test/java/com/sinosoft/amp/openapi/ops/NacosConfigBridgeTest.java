package com.sinosoft.amp.openapi.ops;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NacosConfigBridgeTest {

    @Test
    void parsePropertiesAndApply() {
        RuntimeConfigService config = new RuntimeConfigService(new OpsProperties(true, 100, 3, 2));
        NacosConfigBridge bridge = new NacosConfigBridge(
                new NacosProperties(false, "127.0.0.1:8848", "", "DEFAULT_GROUP", "amp-platform.properties", null, null),
                config);
        bridge.applyRaw("""
                # nacos config
                rateLimitEnabled=false
                rateLimitPerMinute=25
                tokenCharsPerToken=8
                tokenCostPer1k=5
                """);
        assertEquals(false, config.rateLimitEnabled());
        assertEquals(25, config.rateLimitPerMinute());
        assertEquals(8, config.tokenCharsPerToken());
        assertEquals(5, config.tokenCostPer1k());
    }

    @Test
    void parseYamlLikeAndApply() {
        RuntimeConfigService config = new RuntimeConfigService(new OpsProperties(true, 100, 3, 2));
        NacosConfigBridge bridge = new NacosConfigBridge(
                new NacosProperties(false, "127.0.0.1:8848", "", "G", "d", null, null),
                config);
        bridge.applyRaw("""
                amp:
                  ops:
                    rate-limit-enabled: true
                    rate-limit-per-minute: 7
                """);
        // 短键 rate-limit-per-minute → rateLimitPerMinute
        assertEquals(7, config.rateLimitPerMinute());
    }

    @Test
    void statusReflectsLocalWhenDisabled() {
        RuntimeConfigService config = new RuntimeConfigService(new OpsProperties(true, 100, 3, 2));
        NacosConfigBridge bridge = new NacosConfigBridge(
                new NacosProperties(false, "127.0.0.1:8848", null, null, null, null, null),
                config);
        bridge.start();
        Map<String, Object> st = bridge.status();
        assertEquals(false, st.get("enabled"));
        assertEquals("local", st.get("source"));
    }

    @Test
    void parseHandlesEquals() {
        Map<String, String> m = NacosConfigBridge.parse("rateLimitPerMinute=9");
        assertEquals("9", m.get("rateLimitPerMinute"));
    }
}
