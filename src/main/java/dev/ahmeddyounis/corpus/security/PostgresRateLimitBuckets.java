package dev.ahmeddyounis.corpus.security;

import io.github.bucket4j.Bucket;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.distributed.jdbc.PrimaryKeyMapper;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import io.github.bucket4j.postgresql.Bucket4jPostgreSQL;
import java.time.Duration;
import javax.sql.DataSource;

/**
 * Buckets held in PostgreSQL, so a user's budget is the configured value across the
 * whole fleet rather than that value per replica. Uses the existing database — no
 * Redis, consistent with the single-datastore decision in ADR 0002 — at the cost of
 * one short SELECT ... FOR UPDATE round trip per rate-limited request.
 *
 * <p>Keys are stored as strings rather than hashed to a {@code long}, so distinct
 * callers can never collide into a shared budget.
 */
public class PostgresRateLimitBuckets implements RateLimitBuckets {

    private final ProxyManager<String> proxyManager;

    public PostgresRateLimitBuckets(DataSource dataSource) {
        this.proxyManager = Bucket4jPostgreSQL.selectForUpdateBasedBuilder(dataSource)
                .primaryKeyMapper(PrimaryKeyMapper.STRING)
                .table("rate_limit_bucket")
                .idColumn("id")
                .stateColumn("state")
                .build();
    }

    @Override
    public Bucket resolve(String key, int permitsPerMinute) {
        BucketConfiguration configuration = BucketConfiguration.builder()
                .addLimit(limit -> limit.capacity(permitsPerMinute)
                        .refillGreedy(permitsPerMinute, Duration.ofMinutes(1)))
                .build();
        return proxyManager.builder().build(key, configuration);
    }
}
