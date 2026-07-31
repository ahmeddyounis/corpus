package dev.ahmeddyounis.corpus.ops;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Fails fast when the configured embedding dimension does not match the
 * vector_store column — which happens after switching embedding models
 * (profiles use different dimensions). The fix is a wipe + re-ingest.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class DimensionGuard implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DimensionGuard.class);

    private final JdbcClient jdbc;
    private final int configuredDimension;

    public DimensionGuard(JdbcClient jdbc, @Value("${corpus.embedding.dimension}") int configuredDimension) {
        this.jdbc = jdbc;
        this.configuredDimension = configuredDimension;
    }

    @Override
    public void run(ApplicationArguments args) {
        String columnType = jdbc.sql("""
                        SELECT format_type(atttypid, atttypmod) FROM pg_attribute
                        WHERE attrelid = 'vector_store'::regclass AND attname = 'embedding'
                        """)
                .query(String.class)
                .single();
        String expected = "vector(" + configuredDimension + ")";
        if (!expected.equals(columnType)) {
            throw new IllegalStateException("""
                    Embedding dimension mismatch: vector_store.embedding is %s but the active profile \
                    configures %s. This happens after switching embedding models. Wipe the store and \
                    re-ingest: `docker compose down -v && docker compose up`, or drop the database \
                    volume for locally managed Postgres.""".formatted(columnType, expected));
        }
        log.info("Embedding dimension check passed ({})", columnType);
    }
}
