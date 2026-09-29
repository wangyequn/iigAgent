package com.sinosoft.amp.openapi.knowledge;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public class JdbcKnowledgeStore implements KnowledgeStore {

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcKnowledgeStore(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static Timestamp ts(Instant i) {
        return i == null ? null : Timestamp.from(i);
    }

    @Override
    public void insertDocument(String id, String tenantId, String title, String source, Instant createdAt) {
        jdbc.update(
                """
                INSERT INTO knowledge_document (id, tenant_id, title, source, status, created_at)
                VALUES (:id, :tenantId, :title, :source, 'ACTIVE', :createdAt)
                ON CONFLICT (id) DO UPDATE
                SET title = EXCLUDED.title, source = EXCLUDED.source
                """,
                new MapSqlParameterSource()
                        .addValue("id", id)
                        .addValue("tenantId", tenantId)
                        .addValue("title", title)
                        .addValue("source", source)
                        .addValue("createdAt", ts(createdAt == null ? Instant.now() : createdAt)));
    }

    @Override
    public void insertChunk(String id, String documentId, String tenantId, int chunkIndex, String content, String embeddingJson) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("documentId", documentId)
                .addValue("tenantId", tenantId)
                .addValue("chunkIndex", chunkIndex)
                .addValue("content", content)
                .addValue("embedding", embeddingJson)
                .addValue("embeddingVec", embeddingJson)
                .addValue("createdAt", ts(Instant.now()));
        try {
            jdbc.update(
                    """
                    INSERT INTO knowledge_chunk (id, document_id, tenant_id, chunk_index, content, embedding, embedding_vec, created_at)
                    VALUES (:id, :documentId, :tenantId, :chunkIndex, :content, :embedding, CAST(:embeddingVec AS vector), :createdAt)
                    ON CONFLICT (id) DO UPDATE
                    SET content = EXCLUDED.content, embedding = EXCLUDED.embedding, embedding_vec = EXCLUDED.embedding_vec
                    """,
                    params);
            return;
        } catch (DataAccessException | IllegalArgumentException ex) {
            // no pgvector / column missing / dimension mismatch → keep JSON-only row
        }
        jdbc.update(
                """
                INSERT INTO knowledge_chunk (id, document_id, tenant_id, chunk_index, content, embedding, created_at)
                VALUES (:id, :documentId, :tenantId, :chunkIndex, :content, :embedding, :createdAt)
                ON CONFLICT (id) DO UPDATE
                SET content = EXCLUDED.content, embedding = EXCLUDED.embedding
                """,
                params);
    }

    @Override
    public List<String> listChunkEmbeddings(String tenantId) {
        return jdbc.queryForList(
                "SELECT embedding FROM knowledge_chunk WHERE tenant_id = :tenantId",
                new MapSqlParameterSource().addValue("tenantId", tenantId),
                String.class);
    }

    @Override
    public List<ChunkRow> listChunks(String tenantId) {
        return jdbc.query(
                """
                SELECT id, document_id, chunk_index, content, embedding
                FROM knowledge_chunk WHERE tenant_id = :tenantId
                """,
                new MapSqlParameterSource().addValue("tenantId", tenantId),
                (rs, i) -> new ChunkRow(
                        rs.getString("id"), rs.getString("document_id"), rs.getInt("chunk_index"),
                        rs.getString("content"), rs.getString("embedding")));
    }

    @Override
    public List<ChunkRow> searchByVector(String tenantId, String queryVecLiteral, int topK) {
        try {
            return jdbc.query(
                    """
                    SELECT id, document_id, chunk_index, content, embedding
                    FROM knowledge_chunk
                    WHERE tenant_id = :tenantId AND embedding_vec IS NOT NULL
                    ORDER BY embedding_vec <=> CAST(:q AS vector)
                    LIMIT :topK
                    """,
                    new MapSqlParameterSource()
                            .addValue("tenantId", tenantId)
                            .addValue("q", queryVecLiteral)
                            .addValue("topK", topK <= 0 ? 5 : topK),
                    (rs, i) -> new ChunkRow(
                            rs.getString("id"), rs.getString("document_id"), rs.getInt("chunk_index"),
                            rs.getString("content"), rs.getString("embedding")));
        } catch (DataAccessException | IllegalArgumentException ex) {
            return List.of();
        }
    }

    @Override
    public Optional<DocRow> findDocument(String tenantId, String id) {
        List<DocRow> rows = jdbc.query(
                """
                SELECT id, tenant_id, title, source, status, created_at
                FROM knowledge_document WHERE tenant_id = :tenantId AND id = :id
                """,
                new MapSqlParameterSource().addValue("tenantId", tenantId).addValue("id", id),
                (rs, i) -> new DocRow(
                        rs.getString("id"), rs.getString("tenant_id"), rs.getString("title"),
                        rs.getString("source"), rs.getString("status"),
                        rs.getTimestamp("created_at") == null ? null : rs.getTimestamp("created_at").toInstant()));
        return rows.stream().findFirst();
    }

    @Override
    public List<DocRow> listDocuments(String tenantId) {
        return jdbc.query(
                """
                SELECT id, tenant_id, title, source, status, created_at
                FROM knowledge_document WHERE tenant_id = :tenantId ORDER BY created_at
                """,
                new MapSqlParameterSource().addValue("tenantId", tenantId),
                (rs, i) -> new DocRow(
                        rs.getString("id"), rs.getString("tenant_id"), rs.getString("title"),
                        rs.getString("source"), rs.getString("status"),
                        rs.getTimestamp("created_at") == null ? null : rs.getTimestamp("created_at").toInstant()));
    }
}
