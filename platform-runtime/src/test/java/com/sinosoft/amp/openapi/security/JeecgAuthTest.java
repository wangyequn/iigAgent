package com.sinosoft.amp.openapi.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JeecgAuthTest {

    private static String fakeJwt(String payloadJson) {
        String header = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"alg\":\"HS256\"}".getBytes(StandardCharsets.UTF_8));
        String payload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payloadJson.getBytes(StandardCharsets.UTF_8));
        return header + "." + payload + ".sig";
    }

    @AfterEach
    void cleanup() {
        AuthContext.clear();
    }

    @Test
    void decodeUsernameAndClientId() {
        String token = fakeJwt("{\"username\":\"zhangsan\",\"clientId\":\"ai-bot\",\"exp\":9999999999}");
        Optional<com.fasterxml.jackson.databind.JsonNode> p = JeecgAuthClient.decodePayload(token);
        assertTrue(p.isPresent());
        assertEquals("zhangsan", JeecgAuthClient.claimText(p.get(), "username"));
        assertEquals("ai-bot", JeecgAuthClient.claimText(p.get(), "clientId"));
    }

    @Test
    void identityHasPerm() {
        JeecgIdentity id = new JeecgIdentity("lisi", "ai-bot", Set.of("order:query"), Set.of("developer"));
        assertTrue(id.hasPerm("order:query"));
        assertTrue(id.hasPerm("ORDER:QUERY"));
        assertFalse(id.hasPerm("order:delete"));
        assertTrue(id.hasAnyRole("developer"));
    }

    @Test
    void authContextHoldsJeecgUser() {
        AuthContext.setJeecg(new JeecgIdentity("wangwu", "ai-bot", Set.of("agent:call"), Set.of()));
        assertEquals("wangwu", AuthContext.actorLabel());
        assertEquals("wangwu", AuthContext.currentJeecg().username());
        AuthContext.clearJeecg();
        assertEquals("anonymous", AuthContext.actorLabel());
    }

    @Test
    void exchangeRequestRecord() {
        JeecgAuthController.ExchangeRequest req = new JeecgAuthController.ExchangeRequest("c1", "ai-bot", "s");
        assertEquals("c1", req.code());
        assertEquals("ai-bot", req.clientId());
    }

    @Test
    void administratorsIdMarksPlatformAdmin() {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        try {
            var adminUser = mapper.readTree("{\"username\":\"stc\",\"administratorsId\":\"1\",\"userIdentity\":2}");
            assertTrue(JeecgAuthClient.isPlatformAdministrator(adminUser));
            var normal = mapper.readTree("{\"username\":\"bob\",\"userIdentity\":2}");
            assertFalse(JeecgAuthClient.isPlatformAdministrator(normal));
            var identityAdmin = mapper.readTree("{\"username\":\"root\",\"userIdentity\":1}");
            assertTrue(JeecgAuthClient.isPlatformAdministrator(identityAdmin));
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}
