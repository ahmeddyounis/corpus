package dev.ahmeddyounis.corpus.security;

import io.github.bucket4j.Bucket;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-instance buckets. Fast and dependency-free, but with N replicas each user's
 * effective budget is N times the configured value — fine for single-instance and
 * local development, which is why it stays the default.
 *
 * <p>The map is bounded in practice by the user population for authenticated keys;
 * anonymous keys are IP-derived, so a size cap guards against unbounded growth.
 */
public class InMemoryRateLimitBuckets implements RateLimitBuckets {

    private static final int MAX_ENTRIES = 100_000;

    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();

    @Override
    public Bucket resolve(String key, int permitsPerMinute) {
        if (buckets.size() > MAX_ENTRIES) {
            // Resetting budgets at this scale beats unbounded memory growth.
            buckets.clear();
        }
        return buckets.computeIfAbsent(key, k -> Bucket.builder()
                .addLimit(limit -> limit.capacity(permitsPerMinute)
                        .refillGreedy(permitsPerMinute, Duration.ofMinutes(1)))
                .build());
    }
}
