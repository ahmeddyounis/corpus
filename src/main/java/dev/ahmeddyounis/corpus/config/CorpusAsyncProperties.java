package dev.ahmeddyounis.corpus.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Executor sizing and the shutdown budget.
 *
 * <p>The termination timeouts must stay below
 * {@code spring.lifecycle.timeout-per-shutdown-phase}, which must itself stay below
 * the platform's kill timeout (Kubernetes {@code terminationGracePeriodSeconds},
 * Fly {@code kill_timeout}) — otherwise the drain is cut short by SIGKILL.
 */
@ConfigurationProperties(prefix = "corpus.async")
public record CorpusAsyncProperties(
        int ingestionConcurrency,
        Duration ingestionTermination,
        Duration chatTermination,
        Duration retrievalTermination) {

    public CorpusAsyncProperties {
        ingestionConcurrency = ingestionConcurrency > 0 ? ingestionConcurrency : 4;
        ingestionTermination = ingestionTermination != null ? ingestionTermination : Duration.ofSeconds(30);
        chatTermination = chatTermination != null ? chatTermination : Duration.ofSeconds(20);
        retrievalTermination = retrievalTermination != null ? retrievalTermination : Duration.ofSeconds(5);
    }
}
