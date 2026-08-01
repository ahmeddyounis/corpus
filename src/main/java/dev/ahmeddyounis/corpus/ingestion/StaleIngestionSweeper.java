package dev.ahmeddyounis.corpus.ingestion;

import dev.ahmeddyounis.corpus.ops.InstanceIdentity;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Startup backstop for ingestion interrupted by a hard kill: uploaded bytes live only
 * in the submitting instance's memory, so work that did not finish cannot be resumed
 * and is marked FAILED with an actionable message, with its partial chunks removed.
 *
 * <p>The sweep is deliberately <em>scoped</em>. It claims only rows this instance
 * itself owns (a restart-in-place keeps the same identity on Kubernetes and Fly) or
 * rows no instance has touched for {@code corpus.ingestion.stale-after}. An unscoped
 * sweep would fail documents that another live replica is still processing — the
 * exact corruption a rolling deploy would otherwise cause. Runs before the demo
 * seeders (order 10/20) so freshly seeded uploads are untouched.
 */
@Component
@Order(5)
public class StaleIngestionSweeper implements ApplicationRunner {

    static final String REASON = "Ingestion interrupted by application restart; please re-upload";

    private static final Logger log = LoggerFactory.getLogger(StaleIngestionSweeper.class);

    private final JdbcClient jdbc;
    private final ChunkStore chunkStore;
    private final InstanceIdentity instance;
    private final CorpusIngestionProperties properties;

    public StaleIngestionSweeper(JdbcClient jdbc, ChunkStore chunkStore, InstanceIdentity instance,
                                 CorpusIngestionProperties properties) {
        this.jdbc = jdbc;
        this.chunkStore = chunkStore;
        this.instance = instance;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        sweep();
    }

    public int sweep() {
        List<SweptDocument> swept = jdbc.sql("""
                        UPDATE documents
                        SET status = 'FAILED', error = :reason, chunk_count = 0, updated_at = now()
                        WHERE status IN ('PENDING', 'PROCESSING')
                          AND (owner_instance = :instanceId
                               OR claimed_at < now() - CAST(:staleAfter AS interval))
                        RETURNING id, user_id
                        """)
                .param("reason", REASON)
                .param("instanceId", instance.id())
                .param("staleAfter", properties.staleAfter().toSeconds() + " seconds")
                .query((rs, rowNum) -> new SweptDocument(
                        UUID.fromString(rs.getString("id")),
                        UUID.fromString(rs.getString("user_id"))))
                .list();

        for (SweptDocument document : swept) {
            try {
                // The old sweeper left these behind: chunks stayed searchable while the
                // document itself read FAILED, so answers could cite a failed document.
                chunkStore.deleteFor(document.userId(), document.id());
            } catch (Exception e) {
                log.warn("Could not remove chunks for swept document {}: {}", document.id(), e.getMessage());
            }
        }
        if (!swept.isEmpty()) {
            log.warn("Marked {} interrupted ingestion(s) FAILED and removed their chunks", swept.size());
        }
        return swept.size();
    }

    private record SweptDocument(UUID id, UUID userId) {
    }
}
