package com.sinosoft.amp.openapi.fs;

import io.agentscope.harness.agent.filesystem.remote.store.BaseStore;
import io.agentscope.harness.agent.filesystem.remote.store.StoreItem;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * T9 PostgreSQL BaseStore（设计 §7.2 JdbcStore 角色）。
 * 路径桶：agents/{agentId}/users/{userId}/... + 文件名；version 乐观锁。
 */
@Component
public class PgBaseStore implements BaseStore {

    private final NamedParameterJdbcTemplate jdbc;
    private final com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();

    public PgBaseStore(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    static String pathKey(List<String> path) {
        if (path == null || path.isEmpty()) {
            return "/";
        }
        StringBuilder sb = new StringBuilder();
        for (String p : path) {
            if (p == null || p.isBlank()) {
                continue;
            }
            sb.append('/').append(p.replace("/", "_"));
        }
        return sb.length() == 0 ? "/" : sb.toString();
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> jsonToMap(String json) {
        if (json == null || json.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readValue(json, Map.class);
        } catch (Exception e) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("raw", json);
            return m;
        }
    }

    @Override
    public StoreItem get(List<String> path, String name) {
        List<StoreItem> rows = jdbc.query(
                """
                SELECT item_name, payload, version FROM base_store
                WHERE path_key = :pathKey AND item_name = :name
                """,
                new MapSqlParameterSource()
                        .addValue("pathKey", pathKey(path))
                        .addValue("name", name),
                (rs, i) -> new StoreItem(rs.getString("item_name"),
                        jsonToMap(rs.getString("payload")), rs.getLong("version")));
        return rows.isEmpty() ? null : rows.get(0);
    }

    @Override
    public void put(List<String> path, String name, Map<String, Object> value) {
        putInternal(path, name, value, null);
    }

    @Override
    public boolean putIfVersion(List<String> path, String name, Map<String, Object> value, long expectedVersion) {
        return putInternal(path, name, value, expectedVersion);
    }

    private boolean putInternal(List<String> path, String name, Map<String, Object> value, Long expectedVersion) {
        String json;
        try {
            json = mapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (Exception e) {
            json = "{}";
        }
        String pk = pathKey(path);
        if (expectedVersion != null) {
            int updated = jdbc.update(
                    """
                    UPDATE base_store SET payload = :payload, version = version + 1, updated_at = :now
                    WHERE path_key = :pathKey AND item_name = :name AND version = :expected
                    """,
                    new MapSqlParameterSource()
                            .addValue("payload", json)
                            .addValue("now", Timestamp.from(Instant.now()))
                            .addValue("pathKey", pk)
                            .addValue("name", name)
                            .addValue("expected", expectedVersion));
            return updated > 0;
        }
        jdbc.update(
                """
                INSERT INTO base_store (path_key, item_name, payload, version, updated_at)
                VALUES (:pathKey, :name, :payload, 1, :now)
                ON CONFLICT (path_key, item_name) DO UPDATE
                SET payload = EXCLUDED.payload,
                    version = base_store.version + 1,
                    updated_at = EXCLUDED.updated_at
                """,
                new MapSqlParameterSource()
                        .addValue("pathKey", pk)
                        .addValue("name", name)
                        .addValue("payload", json)
                        .addValue("now", Timestamp.from(Instant.now())));
        return true;
    }

    @Override
    public List<StoreItem> search(List<String> path, int offset, int limit) {
        String prefix = pathKey(path);
        // 子路径：/a/b 匹配 /a/b 与 /a/b/...
        return jdbc.query(
                """
                SELECT item_name, payload, version FROM base_store
                WHERE path_key = :p1 OR path_key LIKE :p2
                ORDER BY path_key, item_name
                LIMIT :limit OFFSET :offset
                """,
                new MapSqlParameterSource()
                        .addValue("p1", prefix)
                        .addValue("p2", prefix.endsWith("/") ? prefix + "%" : prefix + "/%")
                        .addValue("limit", Math.max(1, limit <= 0 ? 50 : limit))
                        .addValue("offset", Math.max(0, offset)),
                (rs, i) -> new StoreItem(rs.getString("item_name"),
                        jsonToMap(rs.getString("payload")), rs.getLong("version")));
    }

    @Override
    public void delete(List<String> path, String name) {
        jdbc.update(
                "DELETE FROM base_store WHERE path_key = :pathKey AND item_name = :name",
                new MapSqlParameterSource().addValue("pathKey", pathKey(path)).addValue("name", name));
    }

    public int countAll() {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM base_store", Map.of(), Integer.class);
        return n == null ? 0 : n;
    }

    public List<Map<String, Object>> debugList(int limit) {
        List<Map<String, Object>> out = new ArrayList<>();
        jdbc.query(
                "SELECT path_key, item_name, version FROM base_store ORDER BY updated_at DESC LIMIT :lim",
                new MapSqlParameterSource().addValue("lim", Math.max(1, limit)),
                rs -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("pathKey", rs.getString("path_key"));
                    m.put("name", rs.getString("item_name"));
                    m.put("version", rs.getLong("version"));
                    out.add(m);
                });
        return out;
    }
}
