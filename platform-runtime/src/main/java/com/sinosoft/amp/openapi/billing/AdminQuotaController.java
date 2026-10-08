package com.sinosoft.amp.openapi.billing;

import com.sinosoft.amp.openapi.security.AuthContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/** S9 配额与用量（admin scope）。 */
@RestController
@RequestMapping("/api/v1/admin")
public class AdminQuotaController {

    private final QuotaService quotas;

    public AdminQuotaController(QuotaService quotas) {
        this.quotas = quotas;
    }

    @GetMapping("/quotas")
    public Map<String, Object> list() {
        AuthContext.requireAdmin();
        String tenant = AuthContext.requireTenantId();
        return Map.of(
                "tenantId", tenant,
                "summary", quotas.summary(tenant),
                "quotas", quotas.listQuotas());
    }

    @PostMapping("/quotas")
    public Map<String, Object> upsert(@Valid @RequestBody QuotaRequest request) {
        AuthContext.requireAdmin();
        String tenant = request.tenantId() == null || request.tenantId().isBlank()
                ? AuthContext.requireTenantId()
                : request.tenantId();
        return quotas.upsertQuota(tenant,
                request.monthlyTokenLimit() == null ? 0 : request.monthlyTokenLimit(),
                request.monthlyRequestLimit() == null ? 0 : request.monthlyRequestLimit());
    }

    @GetMapping("/usage")
    public Map<String, Object> usage(@RequestParam(defaultValue = "50") int limit) {
        AuthContext.requireAdmin();
        String tenant = AuthContext.requireTenantId();
        return Map.of(
                "summary", quotas.summary(tenant),
                "items", quotas.recentUsage(tenant, limit));
    }

    /** 月度用量报表（按 agent 汇总）。 */
    @GetMapping("/usage/report")
    public Map<String, Object> report(
            @RequestParam int year,
            @RequestParam int month) {
        AuthContext.requireAdmin();
        String tenant = AuthContext.requireTenantId();
        return Map.of(
                "tenantId", tenant,
                "year", year,
                "month", month,
                "items", quotas.monthlyReport(tenant, year, month));
    }

    /** 导出区间用量 CSV（附件 usage.csv）。 */
    @GetMapping(value = "/usage/export", produces = "text/csv")
    public ResponseEntity<byte[]> export(
            @RequestParam String from,
            @RequestParam String to) {
        AuthContext.requireAdmin();
        String tenant = AuthContext.requireTenantId();
        String csv = quotas.exportCsv(tenant, from, to);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"usage.csv\"")
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(csv.getBytes(StandardCharsets.UTF_8));
    }

    public record QuotaRequest(
            String tenantId,
            Long monthlyTokenLimit,
            Long monthlyRequestLimit
    ) {
    }
}
