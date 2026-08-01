package dev.ahmeddyounis.corpus.config;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.core.task.TaskRejectedException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Regression tests for the shutdown bug: {@code ExecutorService.close()} blocks on
 * {@code awaitTermination(Long.MAX_VALUE)}, so a pod would be SIGKILLed mid-work
 * rather than draining within its grace period.
 */
class AsyncConfigTest {

    private final CorpusAsyncProperties properties = new CorpusAsyncProperties(
            1, Duration.ofSeconds(2), Duration.ofSeconds(2), Duration.ofSeconds(2));

    @Test
    void closeReturnsWithinTheTerminationBudgetEvenWithWorkInFlight() throws Exception {
        AsyncConfig config = new AsyncConfig(properties);
        SimpleAsyncTaskExecutor executor = config.ingestionExecutor(config.taskDecorator());
        CountDownLatch started = new CountDownLatch(1);
        AtomicBoolean interrupted = new AtomicBoolean();

        executor.submit(() -> {
            started.countDown();
            try {
                Thread.sleep(Duration.ofSeconds(60));
            } catch (InterruptedException e) {
                interrupted.set(true);
                Thread.currentThread().interrupt();
            }
        });
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();

        long start = System.nanoTime();
        executor.close();
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(elapsedMs)
                .as("close() must respect the termination timeout, not block on the task")
                .isLessThan(15_000);
        assertThat(interrupted).as("the lingering task is interrupted, not abandoned").isTrue();
    }

    @Test
    void ingestionShedsLoadBeyondTheConcurrencyLimit() throws Exception {
        AsyncConfig config = new AsyncConfig(properties);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch started = new CountDownLatch(1);

        try (SimpleAsyncTaskExecutor executor = config.ingestionExecutor(config.taskDecorator())) {
            executor.submit(() -> {
                started.countDown();
                try {
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();

            assertThatThrownBy(() -> executor.submit(() -> { }))
                    .isInstanceOf(TaskRejectedException.class);

            release.countDown();
        }
    }

    @Test
    void defaultsAreAppliedWhenPropertiesAreAbsent() {
        CorpusAsyncProperties defaults = new CorpusAsyncProperties(0, null, null, null);

        assertThat(defaults.ingestionConcurrency()).isEqualTo(4);
        assertThat(defaults.ingestionTermination()).isEqualTo(Duration.ofSeconds(30));
        assertThat(defaults.chatTermination()).isEqualTo(Duration.ofSeconds(20));
        assertThat(defaults.retrievalTermination()).isEqualTo(Duration.ofSeconds(5));
    }
}
