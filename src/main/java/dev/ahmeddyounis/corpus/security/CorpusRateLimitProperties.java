package dev.ahmeddyounis.corpus.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Request budgets: {@code rpm} per authenticated user ({@code CORPUS_RATE_LIMIT_RPM}),
 * {@code tokenRpm} per client IP on the anonymous token endpoint
 * ({@code CORPUS_TOKEN_RATE_LIMIT_RPM}) to slow credential stuffing.
 *
 * <p>{@code backend} chooses where buckets live: {@code memory} (per instance — with
 * N replicas each user effectively gets N times the budget) or {@code postgres}
 * (fleet-wide, at one short database round trip per rate-limited request).
 */
@ConfigurationProperties(prefix = "corpus.rate-limit")
public record CorpusRateLimitProperties(int rpm, int tokenRpm, String backend) {

    public CorpusRateLimitProperties {
        backend = backend == null || backend.isBlank() ? "memory" : backend.strip().toLowerCase();
        if (!backend.equals("memory") && !backend.equals("postgres")) {
            throw new IllegalArgumentException(
                    "corpus.rate-limit.backend must be 'memory' or 'postgres', got: " + backend);
        }
    }

    public boolean distributed() {
        return "postgres".equals(backend);
    }
}
