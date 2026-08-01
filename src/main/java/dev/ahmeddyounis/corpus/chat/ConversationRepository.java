package dev.ahmeddyounis.corpus.chat;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.PagingAndSortingRepository;

public interface ConversationRepository extends CrudRepository<ConversationEntity, UUID>,
        PagingAndSortingRepository<ConversationEntity, UUID> {

    Page<ConversationEntity> findByUserId(UUID userId, Pageable pageable);

    Optional<ConversationEntity> findByIdAndUserId(UUID id, UUID userId);
}
