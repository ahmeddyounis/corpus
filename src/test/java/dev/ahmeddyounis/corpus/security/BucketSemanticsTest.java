package dev.ahmeddyounis.corpus.security;

import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import java.time.Duration;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BucketSemanticsTest {

    @Test
    void fourthConsumeIsRejected() {
        Bucket bucket = Bucket.builder()
                .addLimit(limit -> limit.capacity(3).refillGreedy(3, Duration.ofMinutes(1)))
                .build();

        for (int i = 0; i < 3; i++) {
            ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
            assertThat(probe.isConsumed()).as("consume %d", i).isTrue();
            assertThat(probe.getRemainingTokens()).isEqualTo(2 - i);
        }

        ConsumptionProbe fourth = bucket.tryConsumeAndReturnRemaining(1);
        assertThat(fourth.isConsumed()).as("fourth consume should be rejected").isFalse();
        assertThat(fourth.getNanosToWaitForRefill()).isPositive();
    }
}
