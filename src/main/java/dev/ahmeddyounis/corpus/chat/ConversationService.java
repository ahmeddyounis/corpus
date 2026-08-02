package dev.ahmeddyounis.corpus.chat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.Message;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ConversationService {

    public record MessageView(String role, String text) {
    }

    public record ConversationHistory(UUID id, String title, Instant createdAt, List<MessageView> messages) {
    }

    public record ConversationSummary(UUID id, String title, Instant createdAt) {
    }

    private final ConversationRepository conversations;
    private final ChatMemoryRepository chatMemoryRepository;

    public ConversationService(ConversationRepository conversations, ChatMemoryRepository chatMemoryRepository) {
        this.conversations = conversations;
        this.chatMemoryRepository = chatMemoryRepository;
    }

    /** Loads an owned conversation or creates a new one titled from the first message. */
    public ConversationEntity resolve(UUID userId, UUID conversationId, String firstMessage) {
        if (conversationId == null) {
            return conversations.save(ConversationEntity.create(userId, firstMessage));
        }
        return conversations.findByIdAndUserId(conversationId, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Conversation not found"));
    }

    public org.springframework.data.domain.Page<ConversationEntity> list(
            UUID userId, org.springframework.data.domain.Pageable pageable) {
        return conversations.findByUserId(userId, pageable);
    }

    public ConversationHistory history(UUID userId, UUID conversationId) {
        ConversationEntity conversation = conversations.findByIdAndUserId(conversationId, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Conversation not found"));
        List<Message> messages = chatMemoryRepository.findByConversationId(conversation.id().toString());
        List<MessageView> views = messages.stream()
                .map(m -> new MessageView(m.getMessageType().getValue(), m.getText()))
                .toList();
        return new ConversationHistory(conversation.id(), conversation.title(), conversation.createdAt(), views);
    }
}
