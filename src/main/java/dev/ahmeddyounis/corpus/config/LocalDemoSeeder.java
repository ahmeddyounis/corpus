package dev.ahmeddyounis.corpus.config;

import dev.ahmeddyounis.corpus.ingestion.DocumentEntity;
import dev.ahmeddyounis.corpus.ingestion.IngestionService;
import dev.ahmeddyounis.corpus.ops.AdvisoryLock;
import dev.ahmeddyounis.corpus.security.UserAccount;
import dev.ahmeddyounis.corpus.security.UserRepository;
import java.io.IOException;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
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

    private final UserRepository users;
    private final IngestionService ingestionService;
    private final AdvisoryLock advisoryLock;

    public LocalDemoSeeder(UserRepository users, IngestionService ingestionService, AdvisoryLock advisoryLock) {
        this.users = users;
        this.ingestionService = ingestionService;
        this.advisoryLock = advisoryLock;
    }

    @Override
    public void run(ApplicationArguments args) {
        // The list-then-upload body is check-then-act: without cluster-wide exclusion,
        // two instances starting together both see an empty corpus and seed it twice.
        advisoryLock.runExclusively(AdvisoryLock.SEED_LOCK, this::seed);
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
                    ingestionService.upload(demo.id(), filename, "text/markdown", resource.getContentAsByteArray());
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
}
