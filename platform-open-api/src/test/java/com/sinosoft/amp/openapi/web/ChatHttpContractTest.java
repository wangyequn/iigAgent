package com.sinosoft.amp.openapi.web;

import com.sinosoft.amp.common.api.ApiError;
import com.sinosoft.amp.openapi.session.SessionContext;
import com.sinosoft.amp.openapi.session.SessionContextException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.ControllerAdvice;

import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 不依赖 HarnessAgent / Redis 的 HTTP 契约测试（健康检查 + 400 session）。
 */
class ChatHttpContractTest {

    private final MockMvc mockMvc = MockMvcBuilders
            .standaloneSetup(new ContractController())
            .setControllerAdvice(new SessionAdvice())
            .build();

    @Test
    void healthIsUp() throws Exception {
        mockMvc.perform(get("/api/v1/health/demo"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void emptySessionReturns400() throws Exception {
        mockMvc.perform(post("/api/v1/agents/demo/messages")
                        .contentType("application/json")
                        .content("""
                                {"tenantId":"acme","userId":"u1","sessionId":"","message":"hi"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SESSION_CONTEXT_INVALID"));
    }

    @RestController
    static class ContractController {
        @GetMapping("/api/v1/health/demo")
        public Map<String, String> health() {
            return Map.of("status", "UP", "agent", "demo");
        }

        @PostMapping("/api/v1/agents/{agentId}/messages")
        public Map<String, String> message(
                @PathVariable String agentId,
                @RequestBody Map<String, String> body) {
            SessionContext.require(
                    body.getOrDefault("tenantId", ""),
                    body.getOrDefault("userId", ""),
                    body.getOrDefault("sessionId", ""));
            return Map.of("agentId", agentId, "ok", "true");
        }
    }

    @ControllerAdvice
    static class SessionAdvice {
        @ExceptionHandler(SessionContextException.class)
        public ResponseEntity<ApiError> handle(SessionContextException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(ApiError.of("SESSION_CONTEXT_INVALID", e.getMessage(), "test"));
        }
    }
}
