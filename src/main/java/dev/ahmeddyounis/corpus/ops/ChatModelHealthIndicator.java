package dev.ahmeddyounis.corpus.ops;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Reports chat-model availability as a detail, not as a verdict.
 *
 * <p>Deliberately <em>not</em> part of the readiness group. The chat provider is
 * shared by every replica, so a provider outage would take the whole fleet out of
 * rotation at once — including {@code /api/search} and the MCP search tools, which
 * need no chat model at all. Provider trouble belongs in alerts, not in a probe.
 *
 * <p>An absent chat model reports UP with {@code configured=false}: that is the
 * keyless profile working as designed, not a fault.
 */
@Component("chatModel")
public class ChatModelHealthIndicator implements HealthIndicator {

    private final ObjectProvider<ChatClient.Builder> chatClientBuilder;
    private final ModelResilience resilience;
    private final String provider;

    public ChatModelHealthIndicator(ObjectProvider<ChatClient.Builder> chatClientBuilder,
                                    ModelResilience resilience,
                                    @Value("${spring.ai.model.chat:none}") String provider) {
        this.chatClientBuilder = chatClientBuilder;
        this.resilience = resilience;
        this.provider = provider;
    }

    @Override
    public Health health() {
        return Health.up()
                .withDetail("provider", provider)
                .withDetail("configured", configured())
                .withDetail("breaker", resilience.chatState().name())
                .build();
    }

    private boolean configured() {
        try {
            return chatClientBuilder.getIfAvailable() != null;
        } catch (BeansException e) {
            // The builder definition can exist while its ChatModel dependency does not.
            return false;
        }
    }
}
