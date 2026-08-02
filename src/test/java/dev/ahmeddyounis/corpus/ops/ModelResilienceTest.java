package dev.ahmeddyounis.corpus.ops;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModelResilienceTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    private ModelResilience resilience(boolean enabled) {
        return new ModelResilience(new CorpusResilienceProperties(
                enabled, 4, 4, 50f, Duration.ofSeconds(120), 100f, Duration.ofSeconds(30)), registry);
    }

    @Test
    void breakerOpensAfterRepeatedProviderFailuresAndThenFailsFastAs503() {
        ModelResilience resilience = resilience(true);

        for (int i = 0; i < 4; i++) {
            assertThatThrownBy(() -> resilience.callChat(() -> {
                throw new IllegalStateException("provider exploded");
            })).isInstanceOf(IllegalStateException.class);
        }
        assertThat(resilience.chatState()).isEqualTo(CircuitBreaker.State.OPEN);

        assertThatThrownBy(() -> resilience.callChat(() -> "should never run"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
    }

    /** A 4xx we raised ourselves is not a provider fault and must not trip the breaker. */
    @Test
    void applicationLevelResponseStatusExceptionsAreIgnored() {
        ModelResilience resilience = resilience(true);

        for (int i = 0; i < 10; i++) {
            assertThatThrownBy(() -> resilience.callChat(() -> {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "bad input");
            })).isInstanceOf(ResponseStatusException.class);
        }

        assertThat(resilience.chatState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void breakerStateIsExportedForAlerting() {
        resilience(true);

        assertThat(registry.find("resilience4j.circuitbreaker.state")
                .tag("name", ModelResilience.CHAT_BREAKER).gauges())
                .as("the alert rules watch this series")
                .isNotEmpty();
    }

    @Test
    void chatAndEmbeddingBreakersAreIndependent() {
        ModelResilience resilience = resilience(true);

        for (int i = 0; i < 4; i++) {
            assertThatThrownBy(() -> resilience.callEmbedding(() -> {
                throw new IllegalStateException("embedding down");
            })).isInstanceOf(IllegalStateException.class);
        }

        assertThat(resilience.chatState())
                .as("an embedding outage must not stop chat from being attempted")
                .isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void disabledResiliencePassesCallsStraightThrough() {
        ModelResilience resilience = resilience(false);

        for (int i = 0; i < 10; i++) {
            assertThatThrownBy(() -> resilience.callChat(() -> {
                throw new IllegalStateException("provider exploded");
            })).isInstanceOf(IllegalStateException.class);
        }

        assertThat(resilience.callChat(() -> "still attempted")).isEqualTo("still attempted");
    }
}
