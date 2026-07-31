package dev.ahmeddyounis.corpus.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Request budgets: {@code rpm} per authenticated user ({@code CORPUS_RATE_LIMIT_RPM}),
 * {@code tokenRpm} per client IP on the anonymous token endpoint
 * ({@code CORPUS_TOKEN_RATE_LIMIT_RPM}) to slow credential stuffing.
 */
@ConfigurationProperties(prefix = "corpus.rate-limit")
public record CorpusRateLimitProperties(int rpm, int tokenRpm) {
}
