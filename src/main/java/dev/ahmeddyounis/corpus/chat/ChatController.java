package dev.ahmeddyounis.corpus.chat;

import dev.ahmeddyounis.corpus.api.PageResponse;
import dev.ahmeddyounis.corpus.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api")
public class ChatController {

    public record ChatRequestBody(UUID conversationId,
                                  @NotBlank @Size(max = 4000) String message,
                                  @Min(1) @Max(20) Integer topK) {
    }

    private final ChatService chatService;
    private final ConversationService conversationService;
    private final CurrentUser currentUser;

    public ChatController(ChatService chatService, ConversationService conversationService,
                          CurrentUser currentUser) {
        this.chatService = chatService;
        this.conversationService = conversationService;
        this.currentUser = currentUser;
    }

    @Operation(summary = "Ask a question over your documents; answer streams as SSE "
            + "(token* → citations → usage → done, or error)")
    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chat(@Valid @RequestBody ChatRequestBody body) {
        return chatService.stream(currentUser.id(), body.conversationId(), body.message(), body.topK());
    }

    @Operation(summary = "List the caller's conversations, newest first")
    @GetMapping("/conversations")
    public PageResponse<ConversationService.ConversationSummary> conversations(
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC)
            Pageable pageable) {
        return PageResponse.of(conversationService.list(currentUser.id(), pageable),
                c -> new ConversationService.ConversationSummary(c.id(), c.title(), c.createdAt()));
    }

    @Operation(summary = "Fetch a conversation's metadata and message history")
    @GetMapping("/conversations/{id}")
    public ConversationService.ConversationHistory conversation(@PathVariable UUID id) {
        return conversationService.history(currentUser.id(), id);
    }
}
