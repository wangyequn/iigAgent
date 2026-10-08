package com.sinosoft.amp.openapi.fs;

import com.sinosoft.amp.openapi.security.AuthContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * T9：Remote BaseStore 状态与调试列表（多副本共享工作区）。
 */
@RestController
@RequestMapping("/api/v1/admin/fs")
public class AdminFsController {

    private final PgBaseStore store;
    private final FsProperties properties;

    public AdminFsController(PgBaseStore store, FsProperties properties) {
        this.store = store;
        this.properties = properties;
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        AuthContext.requireTenantId();
        return Map.of(
                "mode", properties.mode() == null ? "remote" : properties.mode(),
                "isolationScope", properties.isolationScope() == null ? "USER" : properties.isolationScope(),
                "backend", "PostgreSQL base_store",
                "itemCount", store.countAll());
    }

    @GetMapping("/items")
    public Map<String, Object> items(@RequestParam(defaultValue = "20") int limit) {
        AuthContext.requireTenantId();
        return Map.of("items", store.debugList(limit), "total", store.countAll());
    }
}
