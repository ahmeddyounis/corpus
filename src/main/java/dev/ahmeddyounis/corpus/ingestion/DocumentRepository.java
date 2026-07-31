package dev.ahmeddyounis.corpus.ingestion;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.repository.CrudRepository;

public interface DocumentRepository extends CrudRepository<DocumentEntity, UUID> {

    List<DocumentEntity> findByUserIdOrderByCreatedAtDesc(UUID userId);

    Optional<DocumentEntity> findByIdAndUserId(UUID id, UUID userId);
}
