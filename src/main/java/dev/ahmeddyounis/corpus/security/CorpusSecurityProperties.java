package dev.ahmeddyounis.corpus.security;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "corpus.security")
public record CorpusSecurityProperties(Jwt jwt, Cors cors) {

    public CorpusSecurityProperties {
        cors = cors != null ? cors : new Cors(List.of());
    }

    /** Empty allowed-origins disables CORS entirely, which is the default. */
    public record Cors(List<String> allowedOrigins) {
        public Cors {
            allowedOrigins = allowedOrigins != null ? allowedOrigins : List.of();
        }
    }

    public record Jwt(String secret, Duration ttl) {
        public Jwt {
            if (ttl == null) {
                ttl = Duration.ofHours(24);
            }
        }
    }
}
