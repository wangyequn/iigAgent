package com.sinosoft.amp.openapi.ops;

import com.sinosoft.amp.openapi.security.AuthContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * S7：OTel 状态（disabled / logging / otlp:...）。
 */
@RestController
@RequestMapping("/api/v1/admin")
public class AdminOtelController {

    private final OtelBridge otel;

    public AdminOtelController(OtelBridge otel) {
        this.otel = otel;
    }

    @GetMapping("/otel")
    public Map<String, Object> status() {
        AuthContext.requireTenantId();
        return otel.status();
    }
}
