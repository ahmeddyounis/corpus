package dev.ahmeddyounis.corpus.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Per-user request budget; {@code rpm} binds from {@code CORPUS_RATE_LIMIT_RPM}. */
@ConfigurationProperties(prefix = "corpus.rate-limit")
public record CorpusRateLimitProperties(int rpm) {
}
