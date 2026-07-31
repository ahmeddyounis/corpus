package dev.ahmeddyounis.corpus.support;

import java.util.ArrayList;
import java.util.List;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

/**
 * Deterministic keyless chat model for tests: streams three token chunks (usage
 * metadata on the final one, mirroring real providers) and cites chunk [1] so
 * citation plumbing is assertable.
 */
public class StubChatModel implements ChatModel {

    public static final List<String> TOKENS = List.of(
            "Based on the context, ",
            "the answer is grounded in [1]. ",
            "STUB_ANSWER_COMPLETE");

    public static final int PROMPT_TOKENS = 42;
    public static final int COMPLETION_TOKENS = 17;

    @Override
    public ChatResponse call(Prompt prompt) {
        return response(String.join("", TOKENS), true);
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        List<ChatResponse> chunks = new ArrayList<>();
        for (int i = 0; i < TOKENS.size(); i++) {
            chunks.add(response(TOKENS.get(i), i == TOKENS.size() - 1));
        }
        return Flux.fromIterable(chunks);
    }

    private ChatResponse response(String text, boolean withUsage) {
        ChatResponseMetadata metadata = withUsage
                ? ChatResponseMetadata.builder().usage(new DefaultUsage(PROMPT_TOKENS, COMPLETION_TOKENS)).build()
                : ChatResponseMetadata.builder().build();
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))), metadata);
    }
}
