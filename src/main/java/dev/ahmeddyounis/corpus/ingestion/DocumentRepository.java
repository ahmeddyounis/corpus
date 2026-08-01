package dev.ahmeddyounis.corpus.ingestion;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.PagingAndSortingRepository;

public interface DocumentRepository extends CrudRepository<DocumentEntity, UUID>,
        PagingAndSortingRepository<DocumentEntity, UUID> {

    Page<DocumentEntity> findByUserId(UUID userId, Pageable pageable);

    List<DocumentEntity> findByUserIdOrderByCreatedAtDesc(UUID userId);

    Optional<DocumentEntity> findByIdAndUserId(UUID id, UUID userId);

    Optional<DocumentEntity> findByUserIdAndFilename(UUID userId, String filename);
}
