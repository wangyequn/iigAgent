package com.sinosoft.amp.openapi.web;

import com.sinosoft.amp.openapi.catalog.CatalogException;
import com.sinosoft.amp.openapi.memory.Mem0AdminClient;
import com.sinosoft.amp.openapi.memory.Mem0Properties;
import com.sinosoft.amp.openapi.memory.ScopedMem0LongTermMemory;
import com.sinosoft.amp.openapi.security.AuthContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Mem0 长期记忆 Admin：状态 / 检索 / 列表 / 更新 / 删除。
 * 对话读写走官方 LongTermMemory；本控制器走 {@link Mem0AdminClient}。
 */
@RestController
@RequestMapping("/api/v1/admin/memories")
public class AdminMemoryController {

    private final Mem0AdminClient admin;
    private final Mem0Properties properties;

    public AdminMemoryController(Mem0AdminClient admin, Mem0Properties properties) {
        this.admin = admin;
        this.properties = properties;
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("provider", "mem0");
        body.put("enabled", properties != null && Boolean.TRUE.equals(properties.enabled()));
        body.put("configured", properties != null && properties.isConfigured());
        body.put("apiType", properties == null ? null : properties.apiType());
        body.put("baseUrl", properties == null ? null : properties.normalizedBaseUrl());
        body.put("autoRecall", properties == null || Boolean.TRUE.equals(properties.autoRecall()));
        body.put("autoCapture", properties == null || Boolean.TRUE.equals(properties.autoCapture()));
        if (properties != null && properties.isConfigured()) {
            body.put("reachable", admin.ping());
        }
        return body;
    }

    @GetMapping
    public Map<String, Object> list(
            @RequestParam(value = "userId", required = false) String userId,
            @RequestParam(value = "agentId", required = false) String agentId,
            @RequestParam(value = "sessionId", required = false) String sessionId,
            @RequestParam(value = "limit", required = false, defaultValue = "20") int limit) {
        String tenant = AuthContext.requireTenantId();
        String mem0User = userId == null || userId.isBlank()
                ? null : ScopedMem0LongTermMemory.mem0UserId(tenant, userId);
        List<Map<String, Object>> items = admin.list(mem0User, agentId, sessionId, limit);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("provider", "mem0");
        body.put("tenantId", tenant);
        body.put("items", items);
        return body;
    }

    @PostMapping("/search")
    public Map<String, Object> search(@Valid @RequestBody SearchRequest request) {
        String tenant = AuthContext.requireTenantId();
        String mem0User = request.userId() == null || request.userId().isBlank()
                ? null : ScopedMem0LongTermMemory.mem0UserId(tenant, request.userId());
        int topK = request.topK() == null || request.topK() <= 0 ? 10 : request.topK();
        List<Map<String, Object>> hits = admin.search(mem0User, request.agentId(), request.sessionId(),
                request.query(), topK);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("provider", "mem0");
        body.put("query", request.query());
        body.put("hits", hits);
        return body;
    }

    @GetMapping("/{memoryId}")
    public Map<String, Object> get(@PathVariable String memoryId) {
        AuthContext.requireTenantId();
        return admin.get(memoryId);
    }

    @PutMapping("/{memoryId}")
    public Map<String, Object> update(@PathVariable String memoryId,
                                      @Valid @RequestBody UpdateRequest request) {
        AuthContext.requireTenantId();
        return admin.update(memoryId, request.text());
    }

    @DeleteMapping("/{memoryId}")
    public Map<String, Object> delete(@PathVariable String memoryId) {
        AuthContext.requireTenantId();
        admin.delete(memoryId);
        return Map.of("deleted", memoryId);
    }

    @DeleteMapping
    public Map<String, Object> deleteAll(
            @RequestParam(value = "userId", required = false) String userId,
            @RequestParam(value = "agentId", required = false) String agentId) {
        String tenant = AuthContext.requireTenantId();
        String mem0User = userId == null || userId.isBlank()
                ? null : ScopedMem0LongTermMemory.mem0UserId(tenant, userId);
        admin.deleteAll(mem0User, agentId);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("deletedAll", true);
        body.put("userId", userId);
        body.put("agentId", agentId);
        return body;
    }

    @ExceptionHandler(CatalogException.class)
    public ResponseEntity<Map<String, Object>> onCatalog(CatalogException e) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", e.code());
        body.put("message", e.getMessage());
        body.put("requestId", UUID.randomUUID().toString());
        return ResponseEntity.badRequest().body(body);
    }

    public record SearchRequest(
            @NotBlank String query,
            String userId,
            String agentId,
            String sessionId,
            Integer topK) {
    }

    public record UpdateRequest(@NotBlank String text) {
    }
}
