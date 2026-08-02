package dev.ahmeddyounis.corpus.retrieval;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Reranking knobs. {@code enabled} is the per-request default that
 * {@code /api/search}'s {@code rerank} flag overrides, which is what makes a
 * hand A/B possible against a live index without a redeploy.
 */
@ConfigurationProperties(prefix = "corpus.rerank")
public record CorpusRerankProperties(boolean enabled) {
}
