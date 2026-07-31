package dev.ahmeddyounis.corpus.chat;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.repository.CrudRepository;

public interface ConversationRepository extends CrudRepository<ConversationEntity, UUID> {

    Optional<ConversationEntity> findByIdAndUserId(UUID id, UUID userId);
}
