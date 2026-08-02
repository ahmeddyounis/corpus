package dev.ahmeddyounis.corpus.ops;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Circuit-breaker settings for model calls. A provider outage should fail fast for
 * everyone rather than have every request wait out its full timeout budget.
 */
@ConfigurationProperties(prefix = "corpus.resilience")
public record CorpusResilienceProperties(
        boolean enabled,
        int slidingWindowSize,
        int minimumNumberOfCalls,
        float failureRateThreshold,
        Duration slowCallDurationThreshold,
        float slowCallRateThreshold,
        Duration waitDurationInOpenState) {

    public CorpusResilienceProperties {
        slidingWindowSize = slidingWindowSize > 0 ? slidingWindowSize : 20;
        minimumNumberOfCalls = minimumNumberOfCalls > 0 ? minimumNumberOfCalls : 5;
        failureRateThreshold = failureRateThreshold > 0 ? failureRateThreshold : 50f;
        slowCallDurationThreshold = slowCallDurationThreshold != null
                ? slowCallDurationThreshold : Duration.ofSeconds(120);
        slowCallRateThreshold = slowCallRateThreshold > 0 ? slowCallRateThreshold : 100f;
        waitDurationInOpenState = waitDurationInOpenState != null
                ? waitDurationInOpenState : Duration.ofSeconds(30);
    }
}
