package dev.ahmeddyounis.corpus.quota;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Daily per-user ceilings.
 *
 * <p>Defaults are deliberately generous. The realistic failure of a quota on a
 * public demo is not overspend, it is a reviewer hitting a wall three questions
 * in — so the default has to be a ceiling on abuse, not a budget for normal use,
 * and {@code enabled: false} has to be one variable away.
 */
@ConfigurationProperties(prefix = "corpus.quota")
public record CorpusQuotaProperties(boolean enabled, long dailyTokens, double dailyCostUsd) {

    public CorpusQuotaProperties {
        dailyTokens = dailyTokens > 0 ? dailyTokens : 1_000_000;
        dailyCostUsd = dailyCostUsd > 0 ? dailyCostUsd : 5.0;
    }
}
