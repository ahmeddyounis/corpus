package dev.ahmeddyounis.corpus.ops;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Cluster-wide mutual exclusion for startup work, using the same PostgreSQL
 * advisory-lock mechanism Flyway uses for migrations.
 *
 * <p>Deliberately {@code pg_advisory_xact_lock} rather than the session-scoped
 * variant: {@link JdbcClient} takes a connection per statement, so a
 * {@code lock}/{@code unlock} pair could land on two different pooled connections
 * and strand the lock forever. The transaction-scoped form shares one connection
 * with the body and is released on commit no matter how the body exits.
 */
@Component
public class AdvisoryLock {

    /** Namespace key for demo/sample seeding. */
    public static final long SEED_LOCK = 8_527_401L;

    private final JdbcClient jdbc;
    private final TransactionTemplate transactionTemplate;

    public AdvisoryLock(JdbcClient jdbc, TransactionTemplate transactionTemplate) {
        this.jdbc = jdbc;
        this.transactionTemplate = transactionTemplate;
    }

    public void runExclusively(long key, Runnable body) {
        transactionTemplate.executeWithoutResult(status -> {
            jdbc.sql("SELECT pg_advisory_xact_lock(:key)").param("key", key).query().listOfRows();
            body.run();
        });
    }
}
