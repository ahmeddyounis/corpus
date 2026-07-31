package dev.ahmeddyounis.corpus.support;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Lives on the test classpath inside the scanned base package, so every
 * integration test context gets a deterministic ChatModel without any API key —
 * which also switches on the ChatClient.Builder auto-configuration.
 */
@Configuration
public class StubChatConfig {

    @Bean
    @Primary
    ChatModel stubChatModel() {
        return new StubChatModel();
    }
}
