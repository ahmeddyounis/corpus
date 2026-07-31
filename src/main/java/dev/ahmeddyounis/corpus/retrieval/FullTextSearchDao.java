package dev.ahmeddyounis.corpus.retrieval;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Keyword leg of hybrid retrieval: PostgreSQL full-text search over the generated
 * {@code content_tsv} column of the vector store table. {@code websearch_to_tsquery}
 * parses raw user input safely (no manual tsquery construction), and all values are
 * bound parameters.
 */
@Component
public class FullTextSearchDao {

    public record FtsHit(UUID chunkId, UUID documentId, String filename, int chunkIndex,
                         String content, double rank) {
    }

    private final JdbcClient jdbc;

    public FullTextSearchDao(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<FtsHit> search(UUID userId, String query, int limit, Collection<UUID> documentIds) {
        boolean filtered = documentIds != null && !documentIds.isEmpty();
        String sql = """
                SELECT id,
                       content,
                       metadata->>'document_id' AS document_id,
                       metadata->>'filename'    AS filename,
                       COALESCE((metadata->>'chunk_index')::int, 0) AS chunk_index,
                       ts_rank_cd(content_tsv, q)::float8 AS rank
                FROM vector_store, websearch_to_tsquery('english', :query) q
                WHERE content_tsv @@ q
                  AND metadata->>'user_id' = :userId
                """ + (filtered ? "  AND metadata->>'document_id' IN (:documentIds)\n" : "") + """
                ORDER BY rank DESC
                LIMIT :limit
                """;

        JdbcClient.StatementSpec spec = jdbc.sql(sql)
                .param("query", query)
                .param("userId", userId.toString())
                .param("limit", limit);
        if (filtered) {
            spec = spec.param("documentIds", documentIds.stream().map(UUID::toString).toList());
        }
        return spec.query((rs, rowNum) -> new FtsHit(
                        UUID.fromString(rs.getString("id")),
                        UUID.fromString(rs.getString("document_id")),
                        rs.getString("filename"),
                        rs.getInt("chunk_index"),
                        rs.getString("content"),
                        rs.getDouble("rank")))
                .list();
    }
}
