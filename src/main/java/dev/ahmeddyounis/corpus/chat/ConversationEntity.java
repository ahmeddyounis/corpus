package dev.ahmeddyounis.corpus.chat;

import java.time.Instant;
import java.util.UUID;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

@Table("conversations")
public record ConversationEntity(@Id UUID id, UUID userId, String title, Instant createdAt) {

    public static ConversationEntity create(UUID userId, String firstMessage) {
        String title = firstMessage == null ? "" : firstMessage.strip();
        if (title.length() > 60) {
            title = title.substring(0, 57) + "...";
        }
        return new ConversationEntity(null, userId, title, Instant.now());
    }
}
