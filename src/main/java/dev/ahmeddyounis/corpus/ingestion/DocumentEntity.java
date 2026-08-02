package dev.ahmeddyounis.corpus.ingestion;

import java.time.Instant;
import java.util.UUID;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

/**
 * A document row. Status transitions after creation go through
 * {@link DocumentLifecycleDao} so they are compare-and-set guarded against
 * concurrent instances; this record is otherwise a plain data carrier.
 */
@Table("documents")
public record DocumentEntity(
        @Id UUID id,
        UUID userId,
        String filename,
        String contentType,
        long sizeBytes,
        DocumentStatus status,
        String error,
        int chunkCount,
        Instant createdAt,
        Instant updatedAt,
        String ownerInstance,
        Instant claimedAt) {

    public static DocumentEntity create(UUID userId, String filename, String contentType,
                                        long sizeBytes, String ownerInstance) {
        Instant now = Instant.now();
        return new DocumentEntity(null, userId, filename, contentType, sizeBytes,
                DocumentStatus.PENDING, null, 0, now, now, ownerInstance, now);
    }
}
