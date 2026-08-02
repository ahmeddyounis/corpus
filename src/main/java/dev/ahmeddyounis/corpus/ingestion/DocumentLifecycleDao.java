package dev.ahmeddyounis.corpus.ingestion;

import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Compare-and-set status transitions for the ingestion lifecycle.
 *
 * <p>Every terminal write is guarded on both the expected status and this instance's
 * ownership, so a row that another instance has since claimed or failed cannot be
 * silently overwritten. A {@code false} return means "someone else owns this now" —
 * the caller must clean up whatever it wrote rather than assume success.
 */
@Component
public class DocumentLifecycleDao {

    private final JdbcClient jdbc;

    public DocumentLifecycleDao(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Takes ownership of a PENDING document. False if it is gone or no longer PENDING. */
    public boolean claim(UUID documentId, String instanceId) {
        return jdbc.sql("""
                        UPDATE documents
                        SET status = 'PROCESSING', owner_instance = :instanceId,
                            claimed_at = now(), error = NULL, updated_at = now()
                        WHERE id = :id AND status = 'PENDING'
                        """)
                .param("id", documentId)
                .param("instanceId", instanceId)
                .update() == 1;
    }

    public boolean markReady(UUID documentId, int chunkCount, String instanceId) {
        return jdbc.sql("""
                        UPDATE documents
                        SET status = 'READY', chunk_count = :chunks, error = NULL, updated_at = now()
                        WHERE id = :id AND status = 'PROCESSING' AND owner_instance = :instanceId
                        """)
                .param("id", documentId)
                .param("chunks", chunkCount)
                .param("instanceId", instanceId)
                .update() == 1;
    }

    public boolean markFailed(UUID documentId, String error, String instanceId) {
        return jdbc.sql("""
                        UPDATE documents
                        SET status = 'FAILED', error = :error, chunk_count = 0, updated_at = now()
                        WHERE id = :id AND status = 'PROCESSING' AND owner_instance = :instanceId
                        """)
                .param("id", documentId)
                .param("error", error)
                .param("instanceId", instanceId)
                .update() == 1;
    }
}
