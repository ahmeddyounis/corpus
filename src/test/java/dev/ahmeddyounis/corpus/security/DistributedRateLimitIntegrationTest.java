package dev.ahmeddyounis.corpus.security;

import dev.ahmeddyounis.corpus.support.AbstractIntegrationTest;
import io.github.bucket4j.Bucket;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Two provider instances stand in for two replicas. With PostgreSQL-backed buckets
 * they share one budget; with in-memory buckets each would get its own — the N×
 * over-permissiveness this backend exists to remove.
 */
class DistributedRateLimitIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private DataSource dataSource;

    @Test
    void budgetsAreSharedAcrossInstances() {
        RateLimitBuckets replicaA = new PostgresRateLimitBuckets(dataSource);
        RateLimitBuckets replicaB = new PostgresRateLimitBuckets(dataSource);
        String key = "user:" + java.util.UUID.randomUUID();

        for (int i = 0; i < 3; i++) {
            Bucket onA = replicaA.resolve(key, 3);
            assertThat(onA.tryConsume(1)).as("consume %d on replica A", i).isTrue();
        }

        Bucket onB = replicaB.resolve(key, 3);
        assertThat(onB.tryConsume(1))
                .as("the second replica must see the budget the first one exhausted")
                .isFalse();
    }

    @Test
    void inMemoryBucketsAreIndependentPerInstance() {
        RateLimitBuckets replicaA = new InMemoryRateLimitBuckets();
        RateLimitBuckets replicaB = new InMemoryRateLimitBuckets();
        String key = "user:" + java.util.UUID.randomUUID();

        assertThat(replicaA.resolve(key, 1).tryConsume(1)).isTrue();
        assertThat(replicaA.resolve(key, 1).tryConsume(1)).isFalse();

        assertThat(replicaB.resolve(key, 1).tryConsume(1))
                .as("documents the N-times-budget caveat of the default backend")
                .isTrue();
    }

    @Test
    void distinctKeysDoNotShareABudget() {
        RateLimitBuckets buckets = new PostgresRateLimitBuckets(dataSource);
        String first = "user:" + java.util.UUID.randomUUID();
        String second = "user:" + java.util.UUID.randomUUID();

        assertThat(buckets.resolve(first, 1).tryConsume(1)).isTrue();
        assertThat(buckets.resolve(first, 1).tryConsume(1)).isFalse();
        assertThat(buckets.resolve(second, 1).tryConsume(1)).isTrue();
    }
}
