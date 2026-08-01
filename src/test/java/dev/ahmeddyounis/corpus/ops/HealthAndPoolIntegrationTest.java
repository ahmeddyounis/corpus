package dev.ahmeddyounis.corpus.ops;

import com.zaxxer.hikari.HikariDataSource;
import dev.ahmeddyounis.corpus.support.AbstractIntegrationTest;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;

import static org.assertj.core.api.Assertions.assertThat;

class HealthAndPoolIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private DataSource dataSource;
    @Autowired
    private JdbcClient jdbc;

    @Test
    void livenessAndReadinessProbesAreServed() {
        assertThat(restClient().get().uri("/actuator/health/liveness")
                .retrieve().toEntity(String.class).getStatusCode().value()).isEqualTo(200);

        ResponseEntity<Map<String, Object>> readiness = restClient().get().uri("/actuator/health/readiness")
                .retrieve()
                .toEntity(new ParameterizedTypeReference<>() {
                });
        assertThat(readiness.getStatusCode().value()).isEqualTo(200);
        assertThat(readiness.getBody().get("status")).isEqualTo("UP");
    }

    /** /actuator/health is permitAll for load balancers; it must not leak internals. */
    @Test
    void anonymousHealthExposesNoComponentDetails() {
        ResponseEntity<Map<String, Object>> anonymous = restClient().get().uri("/actuator/health")
                .retrieve()
                .toEntity(new ParameterizedTypeReference<>() {
                });

        assertThat(anonymous.getStatusCode().value()).isEqualTo(200);
        assertThat(anonymous.getBody()).containsKey("status").doesNotContainKey("components");
    }

    @Test
    void authenticatedHealthReportsChatModelStateWithoutGatingReadiness() {
        ResponseEntity<Map<String, Object>> authorized = restClient().get().uri("/actuator/health")
                .header("Authorization", "Bearer " + demoToken())
                .retrieve()
                .toEntity(new ParameterizedTypeReference<>() {
                });

        assertThat(authorized.getStatusCode().value()).isEqualTo(200);
        @SuppressWarnings("unchecked")
        Map<String, Object> components = (Map<String, Object>) authorized.getBody().get("components");
        assertThat(components).containsKey("chatModel");

        // The keyless test profile has no chat model, and that is a designed state:
        // it must be reported as a detail, not as a readiness failure.
        @SuppressWarnings("unchecked")
        Map<String, Object> chatModel = (Map<String, Object>) components.get("chatModel");
        assertThat(chatModel.get("status")).isEqualTo("UP");
        @SuppressWarnings("unchecked")
        Map<String, Object> details = (Map<String, Object>) chatModel.get("details");
        assertThat(details).containsKeys("provider", "configured", "breaker");
    }

    @Test
    void connectionPoolIsExplicitlySized() throws Exception {
        HikariDataSource hikari = dataSource.unwrap(HikariDataSource.class);

        assertThat(hikari.getPoolName()).isEqualTo("corpus-pool");
        assertThat(hikari.getMaximumPoolSize()).isEqualTo(10);
        assertThat(hikari.getConnectionTimeout()).isEqualTo(3000);
    }

    /**
     * The driver-options syntax is a known silent-failure spot, so assert the setting
     * as the server actually sees it rather than trusting the property.
     */
    @Test
    void statementTimeoutIsAppliedOnRealConnections() {
        String statementTimeout = jdbc.sql("SELECT current_setting('statement_timeout')")
                .query(String.class)
                .single();
        String idleTimeout = jdbc.sql("SELECT current_setting('idle_in_transaction_session_timeout')")
                .query(String.class)
                .single();

        assertThat(statementTimeout).isEqualTo("15s");
        assertThat(idleTimeout).isEqualTo("30s");
    }

    @Test
    void poolMetricsAreScrapableForSaturationAlerts() {
        String metrics = restClient().get().uri("/actuator/prometheus").retrieve().body(String.class);

        assertThat(metrics).contains("hikaricp_connections_max");
        assertThat(metrics).contains("pool=\"corpus-pool\"");
    }
}
