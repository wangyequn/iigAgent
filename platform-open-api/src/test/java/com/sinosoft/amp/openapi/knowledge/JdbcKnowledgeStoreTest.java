package com.sinosoft.amp.openapi.knowledge;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JdbcKnowledgeStoreTest {

    @Test
    @SuppressWarnings("unchecked")
    void insertChunkWritesEmbeddingVecWhenAvailable() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        when(jdbc.update(contains("embedding_vec"), any(MapSqlParameterSource.class))).thenReturn(1);
        JdbcKnowledgeStore store = new JdbcKnowledgeStore(jdbc);

        store.insertChunk("c1", "d1", "acme", 0, "hello", "[0.5,-0.25]");

        verify(jdbc, times(1)).update(contains("embedding_vec"), any(MapSqlParameterSource.class));
        verify(jdbc, never()).update(contains("embedding, created_at"), any(MapSqlParameterSource.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void insertChunkFallsBackToJsonWhenPgvectorMissing() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        when(jdbc.update(contains("embedding_vec"), any(MapSqlParameterSource.class)))
                .thenThrow(new DataAccessResourceFailureException("column embedding_vec does not exist"));
        when(jdbc.update(contains("embedding, created_at"), any(MapSqlParameterSource.class))).thenReturn(1);
        JdbcKnowledgeStore store = new JdbcKnowledgeStore(jdbc);

        store.insertChunk("c1", "d1", "acme", 0, "hello", "[0.5,-0.25]");

        verify(jdbc, times(1)).update(contains("embedding_vec"), any(MapSqlParameterSource.class));
        verify(jdbc, times(1)).update(contains("embedding, created_at"), any(MapSqlParameterSource.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void searchByVectorReturnsRowsFromSql() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        KnowledgeStore.ChunkRow row = new KnowledgeStore.ChunkRow("c1", "d1", 0, "hello", "[1,0]");
        when(jdbc.query(contains("<=>"), any(MapSqlParameterSource.class), any(RowMapper.class)))
                .thenReturn(List.of(row));
        JdbcKnowledgeStore store = new JdbcKnowledgeStore(jdbc);

        List<KnowledgeStore.ChunkRow> hits = store.searchByVector("acme", "[1,0]", 3);

        assertEquals(1, hits.size());
        assertEquals("c1", hits.get(0).id());
        verify(jdbc).query(contains("<=>"), any(MapSqlParameterSource.class), any(RowMapper.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void searchByVectorReturnsEmptyWhenPgvectorUnavailable() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        when(jdbc.query(anyString(), any(MapSqlParameterSource.class), any(RowMapper.class)))
                .thenThrow(new DataAccessResourceFailureException("operator does not exist: vector <=> vector"));
        JdbcKnowledgeStore store = new JdbcKnowledgeStore(jdbc);

        assertTrue(store.searchByVector("acme", "[1,0]", 3).isEmpty());
    }

    @Test
    void embeddingPropertiesPgvectorDefaultsOff() {
        EmbeddingProperties off = new EmbeddingProperties("hash", null, null, null, null, null, null);
        assertEquals(false, off.pgvectorEnabled());
        EmbeddingProperties on = new EmbeddingProperties("hash", null, null, null, null, true, null);
        assertEquals(true, on.pgvectorEnabled());
        EmbeddingProperties alias = new EmbeddingProperties("hash", null, null, null, null, null, true);
        assertEquals(true, alias.pgvectorEnabled());
    }
}
