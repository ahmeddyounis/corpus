package dev.ahmeddyounis.corpus.security;

import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RateLimitConfig {

    private static final Logger log = LoggerFactory.getLogger(RateLimitConfig.class);

    @Bean
    RateLimitBuckets rateLimitBuckets(CorpusRateLimitProperties properties, DataSource dataSource) {
        if (properties.distributed()) {
            log.info("Rate limiting is fleet-wide (PostgreSQL-backed buckets)");
            return new PostgresRateLimitBuckets(dataSource);
        }
        return new InMemoryRateLimitBuckets();
    }
}
