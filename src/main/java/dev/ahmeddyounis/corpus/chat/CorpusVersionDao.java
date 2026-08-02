package dev.ahmeddyounis.corpus.chat;

import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Monotonic per-user corpus stamp. Bumping it invalidates every cached answer
 * for that user at once, without touching a cache row: entries carry the version
 * they were computed under and simply stop matching.
 */
@Repository
public class CorpusVersionDao {

    private final JdbcClient jdbc;

    public CorpusVersionDao(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public long current(UUID userId) {
        return jdbc.sql("SELECT version FROM corpus_version WHERE user_id = :userId")
                .param("userId", userId)
                .query(Long.class)
                .optional()
                .orElse(1L);
    }

    /**
     * Atomic in one statement, so concurrent ingestions on different replicas
     * cannot lose an increment — a lost bump would leave stale answers reachable.
     */
    public long bump(UUID userId) {
        return jdbc.sql("""
                        INSERT INTO corpus_version (user_id, version) VALUES (:userId, 2)
                        ON CONFLICT (user_id)
                        DO UPDATE SET version = corpus_version.version + 1, updated_at = now()
                        RETURNING version
                        """)
                .param("userId", userId)
                .query(Long.class)
                .single();
    }
}
