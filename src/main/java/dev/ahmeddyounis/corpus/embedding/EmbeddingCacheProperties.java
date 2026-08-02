package dev.ahmeddyounis.corpus.embedding;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Embedding cache sizing.
 *
 * @param l1MaxEntries in-process entries per replica. Each costs
 *                     {@code dimension x 4} bytes, so 10k x 384 dims is roughly
 *                     15 MB — sized to stay well inside the container's heap.
 * @param l2MaxEntries entries per namespace in Postgres, trimmed by last use.
 */
@ConfigurationProperties(prefix = "corpus.embedding.cache")
public record EmbeddingCacheProperties(boolean enabled, int l1MaxEntries, int l2MaxEntries) {

    public EmbeddingCacheProperties {
        l1MaxEntries = l1MaxEntries > 0 ? l1MaxEntries : 10_000;
        l2MaxEntries = l2MaxEntries > 0 ? l2MaxEntries : 100_000;
    }
}
