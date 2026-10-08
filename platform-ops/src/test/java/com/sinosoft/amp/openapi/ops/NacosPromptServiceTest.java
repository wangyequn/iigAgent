package com.sinosoft.amp.openapi.ops;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NacosPromptServiceTest {

    private static NacosAiProperties props(boolean promptEnabled) {
        return new NacosAiProperties(
                promptEnabled, "127.0.0.1:8848", "shared", "public", null, null, "/nacos",
                NacosAiProperties.SYNC_NACOS_TO_LOCAL, NacosAiProperties.AGENT_CARD_DISCOVER_ONLY,
                "serverIdentity", "security", false, promptEnabled, true, "stable", "latest",
                "amp.agent.", 60_000L, "amp-");
    }

    @Test
    void promptKeyUsesPrefix() {
        assertEquals("amp.agent.demo.sys", NacosPromptService.promptKey("amp.agent.", "demo"));
        assertEquals("amp.agent.demo.sys", NacosPromptService.promptKey(null, "demo"));
    }

    @Test
    void disabledFallsBack() {
        NacosPromptService svc = new NacosPromptService(props(false), new NacosAiAdminClient(props(false), null));
        assertFalse(svc.enabled());
        assertEquals("fallback", svc.resolveSysPrompt("acme", "demo", 1, "fallback"));
    }

    @Test
    void extractTemplateFromVersionDownload() {
        String raw = """
                ---
                promptKey: "amp.agent.demo.sys"
                version: "1.0.0"
                ---

                # amp.agent.demo.sys @ 1.0.0

                ## Template

                ```
                Hello-from-Nacos-Prompt
                ```
                """;
        assertEquals("Hello-from-Nacos-Prompt", NacosPromptService.extractTemplateBlock(raw));
    }

    @Test
    void statusReflectsConfig() {
        NacosAiProperties p = props(true);
        NacosPromptService svc = new NacosPromptService(p, new NacosAiAdminClient(p, null));
        Map<String, Object> st = svc.status();
        assertEquals(Boolean.TRUE, st.get("enabled"));
        assertEquals("amp.agent.", st.get("keyPrefix"));
        assertEquals(Boolean.TRUE, st.get("pinPublished"));
    }
}
