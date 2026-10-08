package com.sinosoft.amp.openapi.billing;

import com.sinosoft.amp.openapi.catalog.CatalogException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * S9 计费配额雏形：月度 token/request 上限 + 用量流水。
 * limit=0 表示不限额；超额抛 QUOTA_EXCEEDED。
 */
@Service
public class QuotaService {

    private static final Logger log = LoggerFactory.getLogger(QuotaService.class);

    private final NamedParameterJdbcTemplate jdbc;

    public QuotaService(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 调用前检查（读本月已用 + 上限）。 */
    public void check(String tenantId) {
        Map<String, Object> q = quotaOf(tenantId);
        long tokenLimit = asLong(q.get("monthly_token_limit"));
        long reqLimit = asLong(q.get("monthly_request_limit"));
        if (tokenLimit <= 0 && reqLimit <= 0) {
            return;
        }
        Map<String, Object> used = monthUsed(tenantId);
        if (reqLimit > 0 && asLong(used.get("requests")) >= reqLimit) {
            throw new CatalogException("QUOTA_EXCEEDED",
                    "monthly request quota exceeded: " + reqLimit);
        }
        if (tokenLimit > 0 && asLong(used.get("tokens")) >= tokenLimit) {
            throw new CatalogException("QUOTA_EXCEEDED",
                    "monthly token quota exceeded: " + tokenLimit);
        }
    }

    /** 调用后记账（失败不阻断业务）。 */
    public void record(String tenantId, String agentId, String sessionId, String userId,
                       int promptTokens, int completionTokens, long costMicros, String source) {
        try {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("tenantId", tenantId);
            p.put("agentId", agentId);
            p.put("sessionId", sessionId);
            p.put("userId", userId);
            p.put("prompt", Math.max(0, promptTokens));
            p.put("completion", Math.max(0, completionTokens));
            p.put("cost", Math.max(0, costMicros));
            p.put("source", source == null ? "managed" : source);
            p.put("at", Timestamp.from(Instant.now()));
            jdbc.update("""
                    INSERT INTO usage_ledger
                      (tenant_id, agent_id, session_id, user_id, prompt_tokens, completion_tokens, cost_micros, source, created_at)
                    VALUES
                      (:tenantId, :agentId, :sessionId, :userId, :prompt, :completion, :cost, :source, :at)
                    """, p);
        } catch (Exception e) {
            log.warn("usage record failed tenant={} err={}", tenantId, e.toString());
        }
    }

    public Map<String, Object> upsertQuota(String tenantId, long monthlyTokenLimit, long monthlyRequestLimit) {
        jdbc.update("""
                INSERT INTO tenant_quota (tenant_id, monthly_token_limit, monthly_request_limit, updated_at)
                VALUES (:tenantId, :tl, :rl, :at)
                ON CONFLICT (tenant_id) DO UPDATE SET
                  monthly_token_limit = EXCLUDED.monthly_token_limit,
                  monthly_request_limit = EXCLUDED.monthly_request_limit,
                  updated_at = EXCLUDED.updated_at
                """, Map.of(
                "tenantId", tenantId,
                "tl", Math.max(0, monthlyTokenLimit),
                "rl", Math.max(0, monthlyRequestLimit),
                "at", Timestamp.from(Instant.now())));
        log.info("quota upserted tenant={} tokenLimit={} requestLimit={}",
                tenantId, monthlyTokenLimit, monthlyRequestLimit);
        return summary(tenantId);
    }

    public List<Map<String, Object>> listQuotas() {
        return jdbc.queryForList("""
                SELECT tenant_id, monthly_token_limit, monthly_request_limit, updated_at
                FROM tenant_quota ORDER BY tenant_id
                """, Map.of());
    }

    public Map<String, Object> summary(String tenantId) {
        Map<String, Object> q = quotaOf(tenantId);
        Map<String, Object> used = monthUsed(tenantId);
        long tokenLimit = asLong(q.get("monthly_token_limit"));
        long reqLimit = asLong(q.get("monthly_request_limit"));
        long tokens = asLong(used.get("tokens"));
        long requests = asLong(used.get("requests"));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tenantId", tenantId);
        out.put("month", LocalDate.now(ZoneOffset.UTC));
        out.put("monthlyTokenLimit", tokenLimit);
        out.put("monthlyRequestLimit", reqLimit);
        out.put("tokensUsed", tokens);
        out.put("requestsUsed", requests);
        out.put("tokensRemaining", tokenLimit <= 0 ? -1 : Math.max(0, tokenLimit - tokens));
        out.put("requestsRemaining", reqLimit <= 0 ? -1 : Math.max(0, reqLimit - requests));
        out.put("costMicrosUsed", asLong(used.get("cost")));
        return out;
    }

    public List<Map<String, Object>> recentUsage(String tenantId, int limit) {
        int n = Math.max(1, Math.min(limit <= 0 ? 50 : limit, 200));
        return jdbc.queryForList("""
                SELECT id, tenant_id, agent_id, session_id, user_id,
                       prompt_tokens, completion_tokens, cost_micros, source, created_at
                FROM usage_ledger
                WHERE tenant_id = :tenantId
                ORDER BY id DESC
                LIMIT :limit
                """, Map.of("tenantId", tenantId, "limit", n));
    }

    /** 月度报表：按 agent_id 汇总 requests / tokens / cost。 */
    public List<Map<String, Object>> monthlyReport(String tenantId, int year, int month) {
        if (month < 1 || month > 12) {
            throw new CatalogException("INVALID_ARGUMENT", "month must be 1..12");
        }
        LocalDate start = LocalDate.of(year, month, 1);
        Timestamp from = Timestamp.from(start.atStartOfDay().toInstant(ZoneOffset.UTC));
        Timestamp to = Timestamp.from(start.plusMonths(1).atStartOfDay().toInstant(ZoneOffset.UTC));
        return jdbc.queryForList("""
                SELECT COALESCE(agent_id, '') AS agent_id,
                       COUNT(*) AS requests,
                       COALESCE(SUM(prompt_tokens), 0) AS prompt_tokens,
                       COALESCE(SUM(completion_tokens), 0) AS completion_tokens,
                       COALESCE(SUM(prompt_tokens + completion_tokens), 0) AS tokens,
                       COALESCE(SUM(cost_micros), 0) AS cost_micros
                FROM usage_ledger
                WHERE tenant_id = :tenantId AND created_at >= :from AND created_at < :to
                GROUP BY COALESCE(agent_id, '')
                ORDER BY agent_id
                """, Map.of("tenantId", tenantId, "from", from, "to", to));
    }

    /**
     * 导出区间用量 CSV（含表头；from/to 为闭区间日期）。
     * 表头：created_at,agent_id,session_id,user_id,prompt_tokens,completion_tokens,cost_micros,source
     */
    public String exportCsv(String tenantId, String from, String to) {
        LocalDate fromDate = parseDate(from, "from");
        LocalDate toDate = parseDate(to, "to");
        if (toDate.isBefore(fromDate)) {
            throw new CatalogException("INVALID_ARGUMENT", "to must be >= from");
        }
        Timestamp fromTs = Timestamp.from(fromDate.atStartOfDay().toInstant(ZoneOffset.UTC));
        Timestamp toTs = Timestamp.from(toDate.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC));
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT created_at, agent_id, session_id, user_id,
                       prompt_tokens, completion_tokens, cost_micros, source
                FROM usage_ledger
                WHERE tenant_id = :tenantId AND created_at >= :from AND created_at < :to
                ORDER BY created_at ASC, id ASC
                """, Map.of("tenantId", tenantId, "from", fromTs, "to", toTs));
        StringBuilder sb = new StringBuilder();
        sb.append("created_at,agent_id,session_id,user_id,prompt_tokens,completion_tokens,cost_micros,source\n");
        for (Map<String, Object> row : rows) {
            sb.append(csvCell(row.get("created_at"))).append(',')
                    .append(csvCell(row.get("agent_id"))).append(',')
                    .append(csvCell(row.get("session_id"))).append(',')
                    .append(csvCell(row.get("user_id"))).append(',')
                    .append(csvCell(row.get("prompt_tokens"))).append(',')
                    .append(csvCell(row.get("completion_tokens"))).append(',')
                    .append(csvCell(row.get("cost_micros"))).append(',')
                    .append(csvCell(row.get("source"))).append('\n');
        }
        return sb.toString();
    }

    private static LocalDate parseDate(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new CatalogException("INVALID_ARGUMENT", name + " is required (yyyy-MM-dd)");
        }
        try {
            return LocalDate.parse(value.trim());
        } catch (DateTimeParseException e) {
            throw new CatalogException("INVALID_ARGUMENT", name + " must be yyyy-MM-dd");
        }
    }

    private static String csvCell(Object o) {
        if (o == null) {
            return "";
        }
        String s;
        if (o instanceof Timestamp ts) {
            s = ts.toInstant().toString();
        } else if (o instanceof Instant i) {
            s = i.toString();
        } else {
            s = String.valueOf(o);
        }
        if (s.indexOf(',') >= 0 || s.indexOf('"') >= 0 || s.indexOf('\n') >= 0 || s.indexOf('\r') >= 0) {
            return '"' + s.replace("\"", "\"\"") + '"';
        }
        return s;
    }

    private Map<String, Object> quotaOf(String tenantId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT tenant_id, monthly_token_limit, monthly_request_limit, updated_at
                FROM tenant_quota WHERE tenant_id = :tenantId
                """, Map.of("tenantId", tenantId));
        return rows.isEmpty() ? Map.of() : rows.get(0);
    }

    private Map<String, Object> monthUsed(String tenantId) {
        Timestamp from = Timestamp.from(LocalDate.now(ZoneOffset.UTC)
                .withDayOfMonth(1).atStartOfDay().toInstant(ZoneOffset.UTC));
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT COALESCE(SUM(prompt_tokens + completion_tokens), 0) AS tokens,
                       COUNT(*) AS requests,
                       COALESCE(SUM(cost_micros), 0) AS cost
                FROM usage_ledger
                WHERE tenant_id = :tenantId AND created_at >= :from
                """, Map.of("tenantId", tenantId, "from", from));
        return rows.isEmpty() ? Map.of() : rows.get(0);
    }

    private static long asLong(Object o) {
        if (o instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(o));
        } catch (Exception e) {
            return 0L;
        }
    }
}
