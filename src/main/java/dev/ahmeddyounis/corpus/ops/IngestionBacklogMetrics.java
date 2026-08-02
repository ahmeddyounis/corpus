package dev.ahmeddyounis.corpus.ops;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Backlog of documents that have not finished ingesting.
 *
 * <p>This is what makes the stale-ingestion sweeper observable in production
 * rather than only in the integration tests: a document stuck in PROCESSING that
 * nothing reclaims shows up here as a backlog that never drains, which is
 * exactly the failure a rolling deploy used to cause.
 *
 * <p>The count is read at most once per {@code REFRESH} and cached, so a scrape
 * interval measured in seconds cannot turn monitoring into database load.
 */
@Component
public class IngestionBacklogMetrics {

    private static final Logger log = LoggerFactory.getLogger(IngestionBacklogMetrics.class);
    private static final Duration REFRESH = Duration.ofSeconds(20);

    private final JdbcClient jdbc;
    private final AtomicLong pending = new AtomicLong();
    private final AtomicLong lastReadNanos = new AtomicLong();

    public IngestionBacklogMetrics(JdbcClient jdbc, MeterRegistry registry) {
        this.jdbc = jdbc;
        registry.gauge("corpus.ingestion.pending.documents", this, IngestionBacklogMetrics::pendingCount);
    }

    double pendingCount() {
        long now = System.nanoTime();
        long last = lastReadNanos.get();
        if (now - last > REFRESH.toNanos() && lastReadNanos.compareAndSet(last, now)) {
            try {
                pending.set(jdbc.sql("""
                                SELECT count(*) FROM documents WHERE status IN ('PENDING', 'PROCESSING')
                                """)
                        .query(Long.class)
                        .single());
            } catch (Exception e) {
                // Serve the previous value: a failed scrape must not fail anything else,
                // and a stale gauge is more useful than a hole in the series.
                log.debug("Could not refresh ingestion backlog gauge: {}", e.toString());
            }
        }
        return pending.get();
    }
}
