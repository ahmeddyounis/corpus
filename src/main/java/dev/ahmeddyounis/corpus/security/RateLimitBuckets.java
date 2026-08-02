package dev.ahmeddyounis.corpus.security;

import io.github.bucket4j.Bucket;

/**
 * Supplies the token bucket for a rate-limit key. Backed either by this instance's
 * heap or by PostgreSQL, so budgets can be either per-instance or fleet-wide.
 */
public interface RateLimitBuckets {

    Bucket resolve(String key, int permitsPerMinute);
}
