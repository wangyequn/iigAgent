package com.sinosoft.amp.openapi.ops;

import com.sinosoft.amp.openapi.security.AuthContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * T8 日志检索：按 sessionId 查会话（Loki / 本地兜底）。
 */
@RestController
@RequestMapping("/api/v1/admin/loki")
public class AdminLokiController {

    private final SessionLogService sessionLogs;

    public AdminLokiController(SessionLogService sessionLogs) {
        this.sessionLogs = sessionLogs;
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        AuthContext.requireTenantId();
        Map<String, Object> search = sessionLogs.searchSession("__none__", 1);
        @SuppressWarnings("unchecked")
        Map<String, Object> loki = (Map<String, Object>) search.get("loki");
        return loki == null ? Map.of() : loki;
    }

    @GetMapping("/search")
    public Map<String, Object> search(
            @RequestParam String sessionId,
            @RequestParam(defaultValue = "50") int limit) {
        AuthContext.requireTenantId();
        return sessionLogs.searchSession(sessionId, limit);
    }
}
