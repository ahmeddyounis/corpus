package dev.ahmeddyounis.corpus.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.core.task.TaskDecorator;

/**
 * Virtual-thread executors with a <em>bounded</em> drain.
 *
 * <p>{@code ExecutorService.close()} (Java 19+) is {@code shutdown()} followed by
 * {@code awaitTermination(Long.MAX_VALUE)} — it blocks indefinitely on the backlog,
 * so under a container grace period the pod is SIGKILLed mid-work, which is the
 * opposite of graceful. {@link SimpleAsyncTaskExecutor} takes an explicit
 * termination timeout, and additionally provides the ingestion bulkhead
 * (concurrency limit + load shedding) and the decorator hook that carries MDC
 * across thread boundaries.
 */
@Configuration
public class AsyncConfig {

    private final CorpusAsyncProperties properties;

    public AsyncConfig(CorpusAsyncProperties properties) {
        this.properties = properties;
    }

    @Bean(destroyMethod = "close")
    SimpleAsyncTaskExecutor ingestionExecutor(TaskDecorator taskDecorator) {
        SimpleAsyncTaskExecutor executor = base("corpus-ingest-", properties.ingestionTermination(), taskDecorator);
        // Parsing and embedding are the heaviest work in the service; cap them and
        // shed beyond the cap rather than queueing without bound.
        executor.setConcurrencyLimit(properties.ingestionConcurrency());
        executor.setRejectTasksWhenLimitReached(true);
        return executor;
    }

    @Bean(destroyMethod = "close")
    SimpleAsyncTaskExecutor retrievalExecutor(TaskDecorator taskDecorator) {
        return base("corpus-retrieve-", properties.retrievalTermination(), taskDecorator);
    }

    @Bean(destroyMethod = "close")
    SimpleAsyncTaskExecutor chatExecutor(TaskDecorator taskDecorator) {
        return base("corpus-chat-", properties.chatTermination(), taskDecorator);
    }

    /** One shared timer for SSE keep-alives; the work per tick is a single write. */
    @Bean(destroyMethod = "shutdownNow")
    java.util.concurrent.ScheduledExecutorService sseHeartbeatScheduler() {
        return java.util.concurrent.Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "corpus-sse-heartbeat");
            thread.setDaemon(true);
            return thread;
        });
    }

    private static SimpleAsyncTaskExecutor base(String threadPrefix, java.time.Duration termination,
                                                TaskDecorator taskDecorator) {
        SimpleAsyncTaskExecutor executor = new SimpleAsyncTaskExecutor(threadPrefix);
        executor.setVirtualThreads(true);
        executor.setTaskTerminationTimeout(termination.toMillis());
        executor.setTaskDecorator(taskDecorator);
        return executor;
    }
}
