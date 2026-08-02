package dev.ahmeddyounis.corpus.config;

import dev.ahmeddyounis.corpus.ingestion.DocumentEntity;
import dev.ahmeddyounis.corpus.ingestion.IngestionCapacityException;
import dev.ahmeddyounis.corpus.ingestion.IngestionService;
import dev.ahmeddyounis.corpus.security.UserAccount;
import dev.ahmeddyounis.corpus.security.UserRepository;
import java.io.IOException;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

/**
 * Seeds the demo user's corpus with the bundled sample documents (the same
 * files the eval golden set is written against), idempotently by filename.
 * Runs after DemoUserSeeder (order 10).
 */
@Component
@Profile({"local", "keyless"})
@Order(20)
public class LocalDemoSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(LocalDemoSeeder.class);
    private static final int MAX_SEED_ATTEMPTS = 120;
    private static final Duration SEED_RETRY_INTERVAL = Duration.ofMillis(500);

    private final UserRepository users;
    private final IngestionService ingestionService;

    public LocalDemoSeeder(UserRepository users, IngestionService ingestionService) {
        this.users = users;
        this.ingestionService = ingestionService;
    }

    /**
     * Deliberately not wrapped in a transaction: ingestion runs asynchronously on
     * other connections, and rows written inside an uncommitted transaction are
     * invisible to those workers, so every task would find nothing to claim and the
     * corpus would sit in PENDING forever. Concurrent seeding is instead made safe
     * by the unique index on (user_id, filename) — a losing replica simply gets a
     * duplicate-key error per file and skips it.
     */
    @Override
    public void run(ApplicationArguments args) {
        seed();
    }

    private void seed() {
        UserAccount demo = users.findByUsername("demo").orElseThrow(
                () -> new IllegalStateException("Demo user missing; DemoUserSeeder should have run first"));
        Set<String> existing = ingestionService.list(demo.id()).stream()
                .map(DocumentEntity::filename)
                .collect(Collectors.toSet());

        int seeded = 0;
        try {
            for (Resource resource : new PathMatchingResourcePatternResolver()
                    .getResources("classpath:samples/*.md")) {
                String filename = resource.getFilename();
                if (filename != null && !existing.contains(filename)) {
                    submitWithBackpressure(demo.id(), filename, resource.getContentAsByteArray());
                    seeded++;
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Could not read the bundled sample documents", e);
        }
        if (seeded > 0) {
            log.info("Seeding {} sample documents for the demo user (ingestion runs asynchronously)", seeded);
        }
    }

    /**
     * There are more samples than ingestion slots, and the bulkhead sheds load rather
     * than queueing. Startup seeding waits for a slot instead of failing — an
     * unhandled rejection here would abort application startup.
     */
    private void submitWithBackpressure(UUID userId, String filename, byte[] content) {
        for (int attempt = 0; attempt < MAX_SEED_ATTEMPTS; attempt++) {
            try {
                ingestionService.upload(userId, filename, "text/markdown", content);
                return;
            } catch (DuplicateKeyException e) {
                // Another instance seeded this file first.
                return;
            } catch (IngestionCapacityException e) {
                try {
                    Thread.sleep(SEED_RETRY_INTERVAL.toMillis());
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
        log.warn("Gave up seeding {}: ingestion stayed at capacity", filename);
    }
}
