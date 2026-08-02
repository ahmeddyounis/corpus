package dev.ahmeddyounis.corpus.chat;

import tools.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Storage for the semantic response cache.
 *
 * <p>Uses the {@code tools.jackson} {@link ObjectMapper}: Spring Boot 4 ships
 * Jackson 3, and only that mapper is auto-configured. The {@code com.fasterxml}
 * one is still on the classpath transitively and injecting it fails at startup.
 *
 * <p>Every statement carries {@code user_id} in its predicate. That is not
 * defence in depth over an application check — it is the only check, placed
 * where it cannot be forgotten by a future caller.
 */
@Repository
public class ResponseCacheDao {

    private static final Logger log = LoggerFactory.getLogger(ResponseCacheDao.class);

    public record CachedAnswer(UUID id, String question, String answer, List<Citation> citations,
                               double similarity) {
    }

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    public ResponseCacheDao(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    /** Phase one: exact question match, which needs no embedding at all. */
    public Optional<CachedAnswer> findExact(UUID userId, String modelKey, long corpusVersion, String questionHash) {
        return jdbc.sql("""
                        SELECT id, question, answer, citations, 1.0 AS similarity
                          FROM response_cache
                         WHERE user_id = :userId AND model_key = :modelKey
                           AND corpus_version = :corpusVersion AND question_hash = :hash
                        """)
                .param("userId", userId)
                .param("modelKey", modelKey)
                .param("corpusVersion", corpusVersion)
                .param("hash", questionHash)
                .query(this::mapRow)
                .optional();
    }

    /**
     * Phase two: nearest neighbour by cosine distance inside this user's
     * partition. An exact scan, deliberately — see the migration for why an
     * approximate index would be both slower here and quietly lossy.
     */
    public Optional<CachedAnswer> findSimilar(UUID userId, String modelKey, long corpusVersion,
                                              String embeddingLiteral, double threshold) {
        return jdbc.sql("""
                        SELECT id, question, answer, citations,
                               1 - (embedding <=> CAST(:embedding AS vector)) AS similarity
                          FROM response_cache
                         WHERE user_id = :userId AND model_key = :modelKey
                           AND corpus_version = :corpusVersion
                           AND 1 - (embedding <=> CAST(:embedding AS vector)) >= :threshold
                         ORDER BY embedding <=> CAST(:embedding AS vector)
                         LIMIT 1
                        """)
                .param("userId", userId)
                .param("modelKey", modelKey)
                .param("corpusVersion", corpusVersion)
                .param("embedding", embeddingLiteral)
                .param("threshold", threshold)
                .query(this::mapRow)
                .optional();
    }

    private CachedAnswer mapRow(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        List<Citation> citations;
        try {
            citations = objectMapper.readValue(rs.getString("citations"),
                    objectMapper.getTypeFactory().constructCollectionType(List.class, Citation.class));
        } catch (Exception e) {
            log.warn("Unreadable cached citations, serving the answer without them: {}", e.toString());
            citations = List.of();
        }
        return new CachedAnswer(rs.getObject("id", UUID.class), rs.getString("question"),
                rs.getString("answer"), citations, rs.getDouble("similarity"));
    }

    public void recordHit(UUID userId, UUID id) {
        jdbc.sql("UPDATE response_cache SET hit_count = hit_count + 1 WHERE id = :id AND user_id = :userId")
                .param("id", id)
                .param("userId", userId)
                .update();
    }

    public void put(UUID userId, String modelKey, long corpusVersion, String questionHash,
                    String question, String answer, List<Citation> citations, String embeddingLiteral) {
        try {
            jdbc.sql("""
                            INSERT INTO response_cache
                                (user_id, model_key, corpus_version, question_hash, question, answer,
                                 citations, embedding)
                            VALUES (:userId, :modelKey, :corpusVersion, :hash, :question, :answer,
                                    CAST(:citations AS jsonb), CAST(:embedding AS vector))
                            ON CONFLICT (user_id, model_key, corpus_version, question_hash) DO NOTHING
                            """)
                    .param("userId", userId)
                    .param("modelKey", modelKey)
                    .param("corpusVersion", corpusVersion)
                    .param("hash", questionHash)
                    .param("question", question)
                    .param("answer", answer)
                    .param("citations", objectMapper.writeValueAsString(citations))
                    .param("embedding", embeddingLiteral)
                    .update();
        } catch (Exception e) {
            log.debug("Response cache write failed, continuing uncached: {}", e.toString());
        }
    }

    public long size(UUID userId) {
        return jdbc.sql("SELECT count(*) FROM response_cache WHERE user_id = :userId")
                .param("userId", userId)
                .query(Long.class)
                .single();
    }

    /** Bounds one user's partition, and drops entries stranded by a corpus bump. */
    public int trim(UUID userId, long corpusVersion, int keep) {
        return jdbc.sql("""
                        DELETE FROM response_cache
                         WHERE user_id = :userId
                           AND (corpus_version <> :corpusVersion
                                OR id NOT IN (SELECT id FROM response_cache
                                               WHERE user_id = :userId AND corpus_version = :corpusVersion
                                               ORDER BY created_at DESC LIMIT :keep))
                        """)
                .param("userId", userId)
                .param("corpusVersion", corpusVersion)
                .param("keep", keep)
                .update();
    }
}
