package com.sinosoft.amp.openapi.web;

import com.sinosoft.amp.openapi.ops.ReadinessSupport;
import com.sinosoft.amp.openapi.security.AuthContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * S8 健康/就绪。ready 含 Redis+PG 探测，供双副本滚动发布使用。
 * 注意：/health/demo 仍在 ChatController，避免重复映射。
 */
@RestController
@RequestMapping("/api/v1/health")
public class HealthController {

    private final ReadinessSupport readiness;

    public HealthController(ReadinessSupport readiness) {
        this.readiness = readiness;
    }

    /**
     * 就绪探针（免鉴权，供 LB / K8s readinessProbe）。
     */
    @GetMapping("/ready")
    public ResponseEntity<Map<String, Object>> ready() {
        ReadinessSupport.ReadyStatus s = readiness.probe();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ready", s.ready());
        body.put("postgres", s.postgres());
        body.put("redis", s.redis());
        body.put("replica", replicaId());
        return ResponseEntity.status(s.ready() ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE).body(body);
    }

    /** 管理侧：实例内部状态（需要 Key）。 */
    @GetMapping("/replica")
    public Map<String, String> replica() {
        AuthContext.requireTenantId();
        return Map.of("replica", replicaId(), "jvm", System.getProperty("java.vm.name", "?"));
    }

    static String replicaId() {
        String env = System.getenv("AMP_REPLICA_ID");
        if (env != null && !env.isBlank()) {
            return env;
        }
        return "r-" + ProcessHandle.current().pid();
    }
}
