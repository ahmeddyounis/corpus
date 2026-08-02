package dev.ahmeddyounis.corpus.ops;

import dev.ahmeddyounis.corpus.support.AbstractIntegrationTest;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The two retrieval legs are wrapped in named Observations, which is what makes the
 * parallel fan-out render as sibling spans once a tracer is present instead of one
 * opaque block.
 *
 * <p>Asserted through the meter registry rather than a span exporter: an Observation
 * feeds both handlers from the same instrumentation, so a recorded timer proves the
 * instrumentation exists, and it does so without depending on which tracer
 * implementation is wired in a given profile.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TracingIntegrationTest extends AbstractIntegrationTest {

    private static final String FIXTURE = """
            # Tracing fixture
            The retrieval fan-out runs a vector leg and a full-text leg in parallel.
            """;

    private String token;

    @BeforeAll
    void seed() {
        token = demoToken();
        if (listDocuments(token).stream().noneMatch(d -> "tracing.md".equals(d.get("filename"))
                && "READY".equals(d.get("status")))) {
            uploadAndAwaitReady(token, "tracing.md", FIXTURE.getBytes(StandardCharsets.UTF_8));
        }
    }

    @Test
    void bothRetrievalLegsAreIndependentlyInstrumented() {
        restClient().post().uri("/api/search")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("query", "retrieval fan-out parallel legs"))
                .retrieve()
                .toEntity(new ParameterizedTypeReference<Object>() {
                });

        String metrics = restClient().get().uri("/actuator/prometheus").retrieve().body(String.class);

        assertThat(metrics)
                .as("each leg must be observable on its own, not merged into the parent")
                .contains("corpus_retrieval_vector_seconds_count")
                .contains("corpus_retrieval_fts_seconds_count");
    }

    @Test
    void springAiObservationsInstrumentTheVectorStore() {
        restClient().post().uri("/api/search")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("query", "spring ai observation coverage"))
                .retrieve()
                .toEntity(new ParameterizedTypeReference<Object>() {
                });

        String metrics = restClient().get().uri("/actuator/prometheus").retrieve().body(String.class);

        // Spring AI ships observation autoconfigurations for chat, embedding, and the
        // vector store; this is what a tracer turns into the RAG span waterfall.
        assertThat(metrics)
                .as("Spring AI's own instrumentation should be active")
                .containsPattern("db_vector_client_operation|gen_ai_client_operation");
    }
}
