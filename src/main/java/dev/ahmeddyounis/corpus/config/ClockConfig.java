package dev.ahmeddyounis.corpus.config;

import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ClockConfig {

    /**
     * UTC, not the host zone. Quota windows are calendar days, and a fleet whose
     * replicas roll over at different local midnights would give some users two
     * budgets in a day and others none.
     */
    @Bean
    @ConditionalOnMissingBean
    Clock clock() {
        return Clock.systemUTC();
    }
}
