package dev.ahmeddyounis.corpus.ops;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics;
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.function.Supplier;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;

/**
 * Circuit breakers around the two external model dependencies.
 *
 * <p>Without one, a provider outage makes every request wait out its full timeout
 * budget before failing, so latency collapses across the board and threads pile up.
 * With one, the first few failures trip the breaker and subsequent calls fail
 * immediately with a 503 until the provider recovers.
 *
 * <p>Deliberately programmatic, using only core Resilience4j: the Spring Boot 4
 * starter is absent from Resilience4j's own BOM, and wrapping explicit call sites
 * avoids proxying and self-invocation surprises. Application-level
 * {@link ResponseStatusException}s (a 4xx we raised ourselves) never count as
 * failures — only genuine provider faults should trip the breaker.
 */
@Component
public class ModelResilience {

    public static final String CHAT_BREAKER = "chatModel";
    public static final String EMBEDDING_BREAKER = "embeddingModel";

    private final boolean enabled;
    private final CircuitBreaker chatBreaker;
    private final CircuitBreaker embeddingBreaker;

    public ModelResilience(CorpusResilienceProperties properties, MeterRegistry meterRegistry) {
        this.enabled = properties.enabled();
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .slidingWindowSize(properties.slidingWindowSize())
                .minimumNumberOfCalls(properties.minimumNumberOfCalls())
                .failureRateThreshold(properties.failureRateThreshold())
                .slowCallDurationThreshold(properties.slowCallDurationThreshold())
                .slowCallRateThreshold(properties.slowCallRateThreshold())
                .waitDurationInOpenState(properties.waitDurationInOpenState())
                .permittedNumberOfCallsInHalfOpenState(2)
                .ignoreExceptions(ResponseStatusException.class, IllegalArgumentException.class)
                .build();
        CircuitBreakerRegistry registry = CircuitBreakerRegistry.of(config);
        this.chatBreaker = registry.circuitBreaker(CHAT_BREAKER);
        this.embeddingBreaker = registry.circuitBreaker(EMBEDDING_BREAKER);
        // Exposes resilience4j_circuitbreaker_state, which the alert rules watch.
        TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(registry).bindTo(meterRegistry);
    }

    public <T> T callChat(Supplier<T> call) {
        return guard(chatBreaker, call, "chat model");
    }

    public <T> T callEmbedding(Supplier<T> call) {
        return guard(embeddingBreaker, call, "embedding model");
    }

    public void runEmbedding(Runnable call) {
        callEmbedding(() -> {
            call.run();
            return null;
        });
    }

    /** Applies the chat breaker to a streaming response without collecting it. */
    public Flux<ChatResponse> streamChat(Flux<ChatResponse> responses) {
        if (!enabled) {
            return responses;
        }
        return responses.transformDeferred(CircuitBreakerOperator.of(chatBreaker))
                .onErrorMap(CallNotPermittedException.class, e -> unavailable("chat model"));
    }

    public CircuitBreaker.State chatState() {
        return chatBreaker.getState();
    }

    private <T> T guard(CircuitBreaker breaker, Supplier<T> call, String what) {
        if (!enabled) {
            return call.get();
        }
        try {
            return breaker.executeSupplier(call);
        } catch (CallNotPermittedException e) {
            throw unavailable(what);
        }
    }

    private static ResponseStatusException unavailable(String what) {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "The " + what + " is temporarily unavailable (circuit breaker open); retry shortly.");
    }
}
