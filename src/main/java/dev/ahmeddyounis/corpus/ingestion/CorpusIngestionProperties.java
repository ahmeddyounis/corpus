package dev.ahmeddyounis.corpus.ingestion;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code staleAfter} is how long an in-flight document may go untouched before any
 * instance may declare it abandoned. It must exceed the slowest legitimate
 * ingestion (bounded by the 25MB upload limit), or a live instance's work could be
 * failed out from under it.
 */
@ConfigurationProperties(prefix = "corpus.ingestion")
public record CorpusIngestionProperties(Duration staleAfter) {

    public CorpusIngestionProperties {
        if (staleAfter == null) {
            staleAfter = Duration.ofMinutes(15);
        }
    }
}
