package dev.ahmeddyounis.corpus.support;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;

/**
 * Lives on the test classpath inside the scanned base package, so every
 * integration test context gets a deterministic ChatModel without any API key —
 * which also switches on the ChatClient.Builder auto-configuration.
 * Disabled under the {@code nightly} profile, where a real provider answers.
 */
@Configuration
@Profile("!nightly")
public class StubChatConfig {

    @Bean
    @Primary
    ChatModel stubChatModel() {
        return new StubChatModel();
    }
}
