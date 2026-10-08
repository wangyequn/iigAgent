package com.sinosoft.amp.openapi.tool;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 控制台机器人工具面：UI 交互契约（不依赖 Spring / 外部服务）。
 */
class ConsoleBotToolsetTest {

    private final ConsoleBotToolset tools = new ConsoleBotToolset(null, "acme");

    @Test
    void uiNavigateReturnsAction() {
        String out = tools.uiNavigate("/agents", "打开 Agent 列表");
        assertTrue(out.contains("\"ok\":true"));
        assertTrue(out.contains("\"type\":\"navigate\""));
        assertTrue(out.contains("/agents"));
    }

    @Test
    void uiFocusReturnsAction() {
        String out = tools.uiFocus("agent", "demo", null);
        assertTrue(out.contains("\"type\":\"focus\""));
        assertTrue(out.contains("\"entityId\":\"demo\""));
    }

    @Test
    void uiNotifyRequiresMessage() {
        assertFalse(tools.uiNotify("info", "   ").contains("\"ok\":true"));
        assertTrue(tools.uiNotify("success", "已保存").contains("\"type\":\"toast\""));
    }

    @Test
    void uiSuggestParsesJsonArray() {
        String out = tools.uiSuggest(
                "[{\"type\":\"navigate\",\"path\":\"/skills\",\"text\":\"打开技能\"},"
                        + "{\"type\":\"ask\",\"text\":\"看看用量\"}]");
        assertTrue(out.contains("\"type\":\"chips\""));
        assertTrue(out.contains("/skills"));
        assertTrue(out.contains("看看用量"));
    }

    @Test
    void uiAskUserCarriesOptions() {
        String out = tools.uiAskUser("要跳到哪？", "权限页,知识库,用量");
        assertTrue(out.contains("\"type\":\"askUser\""));
        assertTrue(out.contains("权限页"));
    }
}
