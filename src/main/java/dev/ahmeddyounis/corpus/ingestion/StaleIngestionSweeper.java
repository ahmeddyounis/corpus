package dev.ahmeddyounis.corpus.ingestion;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * A restart kills in-flight ingestion tasks (they run on in-process executors),
 * which would strand documents in PENDING/PROCESSING forever. On startup, mark
 * them FAILED so users see an actionable state and can re-upload. Runs before
 * the demo seeders (order 10/20) so freshly seeded uploads are untouched.
 * Assumes a single application instance (true for this deployment model).
 */
@Component
@Order(5)
public class StaleIngestionSweeper implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(StaleIngestionSweeper.class);

    private final JdbcClient jdbc;

    public StaleIngestionSweeper(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void run(ApplicationArguments args) {
        sweep();
    }

    public int sweep() {
        int swept = jdbc.sql("""
                        UPDATE documents
                        SET status = 'FAILED',
                            error = 'Ingestion interrupted by application restart; please re-upload',
                            updated_at = now()
                        WHERE status IN ('PENDING', 'PROCESSING')
                        """)
                .update();
        if (swept > 0) {
            log.warn("Marked {} interrupted ingestion(s) FAILED after restart", swept);
        }
        return swept;
    }
}
