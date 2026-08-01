package dev.ahmeddyounis.corpus.ops;

import dev.ahmeddyounis.corpus.support.AbstractIntegrationTest;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

class MetricsIntegrationTest extends AbstractIntegrationTest {

    private static final String FIXTURE = """
            # Metrics fixture
            The observability pipeline records token usage, estimated cost, and phase
            latencies for retrieval, first token, and full response.
            """;

    @Test
    void chatFlowPopulatesPrometheusMetrics() throws Exception {
        String token = demoToken();
        if (listDocuments(token).stream().noneMatch(d -> "metrics.md".equals(d.get("filename"))
                && "READY".equals(d.get("status")))) {
            uploadAndAwaitReady(token, "metrics.md", FIXTURE.getBytes(StandardCharsets.UTF_8));
        }

        String sse;
        try (HttpClient http = HttpClient.newHttpClient()) {
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/chat"))
                    .header("Authorization", "Bearer " + token)
                    .header("Content-Type", "application/json")
                    .header("Accept", "text/event-stream")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            "{\"message\":\"What does the observability pipeline record?\"}"))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);
            sse = response.body();
        }

        // The usage event carries the estimated cost (stub model priced as "unknown" in tests).
        assertThat(sse).contains("estimatedCostUsd");

        ResponseEntity<String> prometheus = restClient().get().uri("/actuator/prometheus")
                .retrieve()
                .toEntity(String.class);
        assertThat(prometheus.getStatusCode().value()).isEqualTo(200);
        String metrics = prometheus.getBody();

        assertThat(metrics)
                .contains("corpus_rag_phase_seconds_bucket")
                .contains("phase=\"retrieval\"")
                .contains("phase=\"embedding\"")
                .contains("phase=\"full_response\"")
                .contains("corpus_llm_tokens_total")
                .contains("direction=\"input\"")
                .contains("corpus_llm_cost_estimate_usd_total");

        // Assert the aggregation-safe summaries by their exact series names: a plain
        // `contains("corpus_retrieval_top_score")` also matches the _observed variant,
        // so it would keep passing even if the gauge disappeared entirely.
        assertThat(metrics)
                .contains("corpus_retrieval_top_score_observed_count")
                .contains("corpus_retrieval_top_score_observed_sum")
                .contains("corpus_retrieval_score_spread_observed_count")
                .contains("corpus_retrieval_score_spread_observed_sum");
        assertThat(metrics.lines().anyMatch(line -> line.startsWith("corpus_retrieval_top_score ")))
                .as("the per-instance gauge is still exported once retrieval has run")
                .isTrue();
        assertThat(metrics.lines().anyMatch(line -> line.startsWith("corpus_retrieval_score_spread ")))
                .isTrue();
    }
}
