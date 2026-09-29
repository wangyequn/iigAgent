package com.sinosoft.amp.openapi.web;

import com.sinosoft.amp.common.api.ApiError;
import com.sinosoft.amp.openapi.catalog.CatalogException;
import com.sinosoft.amp.openapi.sandbox.DockerSandboxService;
import com.sinosoft.amp.openapi.security.AuthContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * T7 沙箱管理：状态 + 受限执行探测。
 */
@RestController
@RequestMapping("/api/v1/admin/sandbox")
public class AdminSandboxController {

    private final DockerSandboxService sandbox;

    public AdminSandboxController(DockerSandboxService sandbox) {
        this.sandbox = sandbox;
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        AuthContext.requireTenantId();
        return sandbox.status();
    }

    /** 受限执行一条命令（Docker 一次性容器）。 */
    @PostMapping("/exec")
    public Map<String, Object> exec(@Valid @RequestBody ExecRequest req) {
        String tenant = AuthContext.requireTenantId();
        DockerSandboxService.ExecResult r = sandbox.exec(
                tenant, "admin", AuthContext.get() == null ? "admin" : tenant,
                "admin-sandbox", req.command());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", r.success());
        body.put("exitCode", r.exitCode());
        body.put("stdout", r.stdout());
        body.put("stderr", r.stderr());
        body.put("containerName", r.containerName());
        body.put("isolationScope", r.isolationScope());
        body.put("durationMs", r.durationMs());
        return body;
    }

    @ExceptionHandler(CatalogException.class)
    public ResponseEntity<ApiError> handle(CatalogException e) {
        HttpStatus status = switch (e.code()) {
            case "SANDBOX_DISABLED" -> HttpStatus.CONFLICT;
            default -> HttpStatus.BAD_REQUEST;
        };
        return ResponseEntity.status(status).body(ApiError.of(e.code(), e.getMessage(), requestId()));
    }

    private static String requestId() {
        ServletRequestAttributes attrs =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attrs != null && attrs.getRequest().getHeader("X-Request-Id") != null) {
            return attrs.getRequest().getHeader("X-Request-Id");
        }
        return UUID.randomUUID().toString();
    }

    public record ExecRequest(@NotBlank String command) {
    }
}
