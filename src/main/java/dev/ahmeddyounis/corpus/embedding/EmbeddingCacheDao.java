package dev.ahmeddyounis.corpus.embedding;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Shared-tier (L2) storage for the content-addressed embedding cache. */
@Repository
public class EmbeddingCacheDao {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingCacheDao.class);

    private final JdbcClient jdbc;

    public EmbeddingCacheDao(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Looks up many hashes in one round trip and refreshes their recency.
     *
     * <p>Returns whatever it found rather than throwing: a cache is an
     * optimisation, and a failed lookup must degrade to a real embedding call
     * instead of failing the ingestion or query that needed it.
     */
    public Map<String, float[]> findAll(String namespace, List<String> hashes) {
        if (hashes.isEmpty()) {
            return Map.of();
        }
        try {
            Map<String, float[]> found = new HashMap<>(hashes.size());
            jdbc.sql("""
                            UPDATE embedding_cache SET last_used_at = now()
                             WHERE namespace = :namespace AND content_hash = ANY(:hashes)
                            RETURNING content_hash, embedding
                            """)
                    .param("namespace", namespace)
                    .param("hashes", hashes.toArray(String[]::new))
                    .query((rs, rowNum) -> {
                        Float[] boxed = (Float[]) rs.getArray("embedding").getArray();
                        float[] vector = new float[boxed.length];
                        for (int i = 0; i < boxed.length; i++) {
                            vector[i] = boxed[i];
                        }
                        found.put(rs.getString("content_hash"), vector);
                        return null;
                    })
                    .list();
            return found;
        } catch (Exception e) {
            log.debug("Embedding cache lookup failed, treating as a miss: {}", e.toString());
            return Map.of();
        }
    }

    /**
     * Writes an entry, tolerating a concurrent writer. Two replicas embedding the
     * same text is wasteful but correct — both computed the same vector — so the
     * conflict only needs to be absorbed, not serialised against.
     */
    public void put(String namespace, String hash, float[] embedding) {
        try {
            Float[] boxed = new Float[embedding.length];
            for (int i = 0; i < embedding.length; i++) {
                boxed[i] = embedding[i];
            }
            jdbc.sql("""
                            INSERT INTO embedding_cache (namespace, content_hash, embedding)
                            VALUES (:namespace, :hash, :embedding)
                            ON CONFLICT (namespace, content_hash) DO NOTHING
                            """)
                    .param("namespace", namespace)
                    .param("hash", hash)
                    .param("embedding", boxed)
                    .update();
        } catch (Exception e) {
            log.debug("Embedding cache write failed, continuing uncached: {}", e.toString());
        }
    }

    public long size(String namespace) {
        return jdbc.sql("SELECT count(*) FROM embedding_cache WHERE namespace = :namespace")
                .param("namespace", namespace)
                .query(Long.class)
                .single();
    }

    /** Trims the coldest entries once a namespace exceeds its cap. */
    public int evictColdest(String namespace, int keep) {
        try {
            return jdbc.sql("""
                            DELETE FROM embedding_cache
                             WHERE namespace = :namespace
                               AND content_hash NOT IN (
                                   SELECT content_hash FROM embedding_cache
                                    WHERE namespace = :namespace
                                    ORDER BY last_used_at DESC
                                    LIMIT :keep)
                            """)
                    .param("namespace", namespace)
                    .param("keep", keep)
                    .update();
        } catch (Exception e) {
            log.debug("Embedding cache eviction failed: {}", e.toString());
            return 0;
        }
    }
}
