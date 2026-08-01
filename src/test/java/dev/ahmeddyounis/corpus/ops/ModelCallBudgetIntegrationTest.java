package dev.ahmeddyounis.corpus.ops;

import dev.ahmeddyounis.corpus.support.AbstractIntegrationTest;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * These assert the <em>effective bound values</em>, never the YAML text. A misspelled
 * or deprecated property key is silently ignored by Spring Boot, which is exactly how
 * the unbounded retry and timeout defaults went unnoticed in the first place.
 */
class ModelCallBudgetIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private Environment environment;
    @Autowired
    private org.springframework.ai.retry.autoconfigure.SpringAiRetryProperties retryProperties;

    /** Reads the bound bean, so a renamed or misspelled key fails here rather than silently. */
    @Test
    void springAiRetriesAreBoundedWellBelowTheTenAttemptDefault() {
        assertThat(retryProperties.getMaxAttempts())
                .as("Spring AI defaults to 10 attempts with escalating backoff; that stacks "
                        + "on top of each SDK's own retries")
                .isLessThanOrEqualTo(2);
        assertThat(retryProperties.isOnClientErrors())
                .as("retrying 4xx wastes the budget on requests that will never succeed")
                .isFalse();
    }

    @Test
    void outboundHttpTimeoutsUseTheNonDeprecatedPropertyKeys() {
        // spring.http.client.* is deprecated in Boot 4; only the plural form binds.
        assertThat(environment.getProperty("spring.http.clients.connect-timeout", Duration.class))
                .isNotNull()
                .isLessThanOrEqualTo(Duration.ofSeconds(10));
        assertThat(environment.getProperty("spring.http.clients.read-timeout", Duration.class))
                .isNotNull()
                .isLessThanOrEqualTo(Duration.ofSeconds(120));
    }

    @Test
    void circuitBreakerStateIsScrapableForAlerting() {
        String metrics = restClient().get().uri("/actuator/prometheus")
                .retrieve()
                .body(String.class);

        assertThat(metrics).contains("resilience4j_circuitbreaker_state");
        assertThat(metrics).contains("name=\"" + ModelResilience.CHAT_BREAKER + "\"");
        assertThat(metrics).contains("name=\"" + ModelResilience.EMBEDDING_BREAKER + "\"");
    }
}
