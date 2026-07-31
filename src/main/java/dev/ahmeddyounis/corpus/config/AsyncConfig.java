package dev.ahmeddyounis.corpus.config;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AsyncConfig {

    /** Ingestion fan-out runs on virtual threads; close() drains on shutdown. */
    @Bean(destroyMethod = "close")
    ExecutorService ingestionExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }

    /** The two retrieval legs (vector + full-text) run in parallel on virtual threads. */
    @Bean(destroyMethod = "close")
    ExecutorService retrievalExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }

    /** SSE chat streams block per-request on a virtual thread while iterating model output. */
    @Bean(destroyMethod = "close")
    ExecutorService chatExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }
}
