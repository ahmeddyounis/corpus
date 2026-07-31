package dev.ahmeddyounis.corpus.security;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "corpus.security")
public record CorpusSecurityProperties(Jwt jwt) {

    public record Jwt(String secret, Duration ttl) {
        public Jwt {
            if (ttl == null) {
                ttl = Duration.ofHours(24);
            }
        }
    }
}
