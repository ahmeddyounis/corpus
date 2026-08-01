package dev.ahmeddyounis.corpus.config;

import dev.ahmeddyounis.corpus.security.DemoUserSeeder;
import dev.ahmeddyounis.corpus.security.UserRepository;
import dev.ahmeddyounis.corpus.support.AbstractIntegrationTest;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Two instances starting simultaneously must not crash on the UNIQUE username —
 * a DuplicateKeyException escaping an ApplicationRunner closes the context and
 * exits the process — and concurrent sample seeding must not create duplicates.
 */
class SeedingConcurrencyIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private DemoUserSeeder demoUserSeeder;
    @Autowired
    private dev.ahmeddyounis.corpus.ingestion.IngestionService ingestionService;
    @Autowired
    private UserRepository users;
    @Autowired
    private JdbcClient jdbc;

    private void runConcurrently(Runnable body) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        Callable<Exception> task = () -> {
            start.await();
            try {
                body.run();
                return null;
            } catch (Exception e) {
                return e;
            }
        };
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Future<Exception> first = pool.submit(task);
            Future<Exception> second = pool.submit(task);
            start.countDown();
            assertThat(first.get(60, TimeUnit.SECONDS)).as("first starter must not fail").isNull();
            assertThat(second.get(60, TimeUnit.SECONDS)).as("second starter must not fail").isNull();
        }
    }

    @Test
    void concurrentDemoUserSeedingCreatesExactlyOneUser() throws Exception {
        jdbc.sql("DELETE FROM users WHERE username = 'demo'").update();

        runConcurrently(() -> demoUserSeeder.run(null));

        Integer count = jdbc.sql("SELECT count(*) FROM users WHERE username = 'demo'")
                .query(Integer.class)
                .single();
        assertThat(count).isEqualTo(1);
        // The account must still be usable, not just present.
        assertThat(users.findByUsername("demo")).isPresent();
        assertThat(demoToken()).isNotBlank();
    }

    /**
     * Concurrent seeding is made safe by the unique index rather than a lock: a
     * losing writer gets a duplicate-key error, which the seeder treats as "already
     * seeded". Proven here with the same shape the seeder uses.
     */
    @Test
    void duplicateSeedingIsRejectedByTheUniqueIndex() throws Exception {
        var demo = users.findByUsername("demo").orElseThrow();
        byte[] content = "# Concurrent seed probe\nContent.".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        java.util.List<Exception> outcomes = java.util.Collections.synchronizedList(new java.util.ArrayList<>());

        runConcurrently(() -> {
            try {
                ingestionService.upload(demo.id(), "concurrent-seed-probe.md", "text/markdown", content);
            } catch (org.springframework.dao.DuplicateKeyException expected) {
                outcomes.add(expected);
            }
        });

        Integer rows = jdbc.sql("""
                        SELECT count(*) FROM documents
                        WHERE user_id = :userId AND filename = 'concurrent-seed-probe.md'
                        """)
                .param("userId", demo.id())
                .query(Integer.class)
                .single();
        assertThat(rows).as("the unique index admits exactly one writer").isEqualTo(1);
        assertThat(outcomes).as("the losing writer saw a duplicate-key error, not a crash").hasSize(1);
    }
}
