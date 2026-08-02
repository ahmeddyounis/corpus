package dev.ahmeddyounis.corpus.chat;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * SSE stream tuning. {@code sseTimeout} must stay below the shortest load-balancer
 * idle timeout in front of the service (ALB and many nginx configs default to 60s),
 * and {@code heartbeatInterval} keeps the connection non-idle for long answers.
 */
@ConfigurationProperties(prefix = "corpus.chat")
public record CorpusChatProperties(Duration sseTimeout, Duration heartbeatInterval) {

    public CorpusChatProperties {
        sseTimeout = sseTimeout != null ? sseTimeout : Duration.ofSeconds(120);
        heartbeatInterval = heartbeatInterval != null ? heartbeatInterval : Duration.ofSeconds(15);
    }
}
