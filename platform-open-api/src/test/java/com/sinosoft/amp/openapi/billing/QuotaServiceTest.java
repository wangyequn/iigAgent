package com.sinosoft.amp.openapi.billing;

import com.sinosoft.amp.openapi.catalog.CatalogException;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class QuotaServiceTest {

    @Test
    void unlimitedQuotaAllows() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        when(jdbc.queryForList(anyString(), any(Map.class))).thenReturn(List.of(Map.of(
                "monthly_token_limit", 0L, "monthly_request_limit", 0L)));
        QuotaService s = new QuotaService(jdbc);
        assertDoesNotThrow(() -> s.check("acme"));
    }

    @Test
    void exceedsRequestQuota() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        when(jdbc.queryForList(anyString(), any(Map.class)))
                .thenReturn(List.of(Map.of("monthly_token_limit", 0L, "monthly_request_limit", 2L)))
                .thenReturn(List.of(Map.of("tokens", 10L, "requests", 2L, "cost", 0L)));
        QuotaService s = new QuotaService(jdbc);
        CatalogException ex = assertThrows(CatalogException.class, () -> s.check("acme"));
        assertTrue(ex.getMessage().contains("quota") || String.valueOf(ex.getMessage()).contains("QUOTA")
                || ex.toString().contains("QUOTA"));
    }

    @Test
    void exceedsTokenQuota() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        when(jdbc.queryForList(anyString(), any(Map.class)))
                .thenReturn(List.of(Map.of("monthly_token_limit", 100L, "monthly_request_limit", 0L)))
                .thenReturn(List.of(Map.of("tokens", 150L, "requests", 3L, "cost", 1L)));
        QuotaService s = new QuotaService(jdbc);
        assertThrows(CatalogException.class, () -> s.check("acme"));
    }

    @Test
    void summaryComputesRemaining() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        when(jdbc.queryForList(anyString(), any(Map.class)))
                .thenReturn(List.of(Map.of("monthly_token_limit", 100L, "monthly_request_limit", 5L)))
                .thenReturn(List.of(Map.of("tokens", 30L, "requests", 1L, "cost", 7L)));
        QuotaService s = new QuotaService(jdbc);
        Map<String, Object> sum = s.summary("acme");
        assertTrue(Long.parseLong(String.valueOf(sum.get("tokensRemaining"))) == 70L);
        assertTrue(Long.parseLong(String.valueOf(sum.get("requestsRemaining"))) == 4L);
    }

    @Test
    void monthlyReportAggregatesByAgent() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        when(jdbc.queryForList(anyString(), any(Map.class))).thenReturn(List.of(
                Map.of("agent_id", "demo", "requests", 3L, "prompt_tokens", 40L,
                        "completion_tokens", 60L, "tokens", 100L, "cost_micros", 7L),
                Map.of("agent_id", "bot", "requests", 1L, "prompt_tokens", 5L,
                        "completion_tokens", 5L, "tokens", 10L, "cost_micros", 1L)));
        QuotaService s = new QuotaService(jdbc);
        List<Map<String, Object>> rows = s.monthlyReport("acme", 2025, 1);
        assertEquals(2, rows.size());
        assertEquals("demo", rows.get(0).get("agent_id"));
        assertEquals(3L, ((Number) rows.get(0).get("requests")).longValue());
        assertEquals(100L, ((Number) rows.get(0).get("tokens")).longValue());
        assertEquals(7L, ((Number) rows.get(0).get("cost_micros")).longValue());
        assertEquals("bot", rows.get(1).get("agent_id"));
    }

    @Test
    void monthlyReportRejectsInvalidMonth() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        QuotaService s = new QuotaService(jdbc);
        assertThrows(CatalogException.class, () -> s.monthlyReport("acme", 2025, 0));
        assertThrows(CatalogException.class, () -> s.monthlyReport("acme", 2025, 13));
    }

    @Test
    void exportCsvHasHeaderAndRows() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("created_at", Timestamp.from(Instant.parse("2025-01-02T03:04:05Z")));
        row.put("agent_id", "demo");
        row.put("session_id", "s1");
        row.put("user_id", "u1");
        row.put("prompt_tokens", 10);
        row.put("completion_tokens", 20);
        row.put("cost_micros", 5L);
        row.put("source", "managed");
        when(jdbc.queryForList(anyString(), any(Map.class))).thenReturn(List.of(row));
        QuotaService s = new QuotaService(jdbc);
        String csv = s.exportCsv("acme", "2025-01-01", "2025-01-31");
        String[] lines = csv.split("\n");
        assertEquals("created_at,agent_id,session_id,user_id,prompt_tokens,completion_tokens,cost_micros,source",
                lines[0]);
        assertTrue(lines[1].startsWith("2025-01-02T03:04:05Z,demo,s1,u1,10,20,5,managed"));
    }

    @Test
    void exportCsvEscapesSpecialChars() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("created_at", Timestamp.from(Instant.parse("2025-01-02T00:00:00Z")));
        row.put("agent_id", "a,\"b");
        row.put("session_id", "s1");
        row.put("user_id", "u1");
        row.put("prompt_tokens", 1);
        row.put("completion_tokens", 2);
        row.put("cost_micros", 0L);
        row.put("source", "managed");
        when(jdbc.queryForList(anyString(), any(Map.class))).thenReturn(List.of(row));
        QuotaService s = new QuotaService(jdbc);
        String csv = s.exportCsv("acme", "2025-01-01", "2025-01-31");
        assertTrue(csv.contains("\"a,\"\"b\""));
    }

    @Test
    void exportCsvRejectsInvalidRange() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        QuotaService s = new QuotaService(jdbc);
        assertThrows(CatalogException.class, () -> s.exportCsv("acme", "2025-01-31", "2025-01-01"));
        assertThrows(CatalogException.class, () -> s.exportCsv("acme", "bad", "2025-01-01"));
    }
}
