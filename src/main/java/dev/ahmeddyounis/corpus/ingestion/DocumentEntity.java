package dev.ahmeddyounis.corpus.ingestion;

import java.time.Instant;
import java.util.UUID;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

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
        Instant updatedAt) {

    public static DocumentEntity create(UUID userId, String filename, String contentType, long sizeBytes) {
        Instant now = Instant.now();
        return new DocumentEntity(null, userId, filename, contentType, sizeBytes,
                DocumentStatus.PENDING, null, 0, now, now);
    }

    public DocumentEntity processing() {
        return new DocumentEntity(id, userId, filename, contentType, sizeBytes,
                DocumentStatus.PROCESSING, null, chunkCount, createdAt, Instant.now());
    }

    public DocumentEntity ready(int chunks) {
        return new DocumentEntity(id, userId, filename, contentType, sizeBytes,
                DocumentStatus.READY, null, chunks, createdAt, Instant.now());
    }

    public DocumentEntity failed(String message) {
        return new DocumentEntity(id, userId, filename, contentType, sizeBytes,
                DocumentStatus.FAILED, message, 0, createdAt, Instant.now());
    }
}
