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
}
