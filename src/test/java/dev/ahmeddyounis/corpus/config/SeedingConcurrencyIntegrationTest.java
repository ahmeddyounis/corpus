package dev.ahmeddyounis.corpus.config;

import dev.ahmeddyounis.corpus.ops.AdvisoryLock;
import dev.ahmeddyounis.corpus.security.DemoUserSeeder;
import dev.ahmeddyounis.corpus.security.UserRepository;
import dev.ahmeddyounis.corpus.support.AbstractIntegrationTest;
import java.util.ArrayList;
import java.util.List;
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
 * exits the process — and startup work guarded by the advisory lock must run
 * exclusively rather than being duplicated.
 */
class SeedingConcurrencyIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private DemoUserSeeder demoUserSeeder;
    @Autowired
    private AdvisoryLock advisoryLock;
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
     * The seeders' bodies are check-then-act. This proves the lock serializes them,
     * using the same read-then-write shape without paying for real ingestion.
     */
    @Test
    void advisoryLockSerializesCheckThenActStartupWork() throws Exception {
        jdbc.sql("DROP TABLE IF EXISTS seed_probe").update();
        jdbc.sql("CREATE TABLE seed_probe (marker varchar(50))").update();
        List<String> failures = new ArrayList<>();

        runConcurrently(() -> advisoryLock.runExclusively(AdvisoryLock.SEED_LOCK, () -> {
            Integer existing = jdbc.sql("SELECT count(*) FROM seed_probe WHERE marker = 'seeded'")
                    .query(Integer.class)
                    .single();
            if (existing == 0) {
                try {
                    Thread.sleep(200); // widen the window a naive check-then-act would lose
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                jdbc.sql("INSERT INTO seed_probe (marker) VALUES ('seeded')").update();
            }
        }));

        Integer seeded = jdbc.sql("SELECT count(*) FROM seed_probe WHERE marker = 'seeded'")
                .query(Integer.class)
                .single();
        assertThat(seeded).as("guarded startup work runs exactly once").isEqualTo(1);
        assertThat(failures).isEmpty();
        jdbc.sql("DROP TABLE seed_probe").update();
    }
}
