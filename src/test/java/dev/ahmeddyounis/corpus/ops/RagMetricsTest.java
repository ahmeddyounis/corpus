package dev.ahmeddyounis.corpus.ops;

import dev.ahmeddyounis.corpus.retrieval.ScoredChunk;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RagMetricsTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final RagMetrics metrics = new RagMetrics(registry);

    private static ScoredChunk chunk(int rank, double rrf) {
        return new ScoredChunk(UUID.randomUUID(), UUID.randomUUID(), "doc.md", 0, "text",
                rank, rrf, null, null);
    }

    /**
     * An idle replica must report no sample at all. Initialising to 0.0 made a
     * replica that had served no traffic look like retrieval quality had collapsed.
     */
    @Test
    void retrievalGaugesStartAsNaNSoIdleInstancesExportNothing() {
        assertThat(registry.get("corpus.retrieval.top.score").gauge().value()).isNaN();
        assertThat(registry.get("corpus.retrieval.score.spread").gauge().value()).isNaN();
    }

    @Test
    void recordingRetrievalPublishesGaugesAndAggregationSafeSummaries() {
        metrics.recordRetrieval(List.of(chunk(1, 0.032), chunk(2, 0.020), chunk(3, 0.012)));

        assertThat(registry.get("corpus.retrieval.top.score").gauge().value()).isEqualTo(0.032);
        assertThat(registry.get("corpus.retrieval.score.spread").gauge().value())
                .isCloseTo(0.020, org.assertj.core.data.Offset.offset(1e-9));

        assertThat(registry.get("corpus.retrieval.top.score.observed").summary().count()).isEqualTo(1);
        assertThat(registry.get("corpus.retrieval.score.spread.observed").summary().count()).isEqualTo(1);
    }

    @Test
    void emptyRetrievalIsRecordedAsZeroRatherThanSkipped() {
        metrics.recordRetrieval(List.of());

        assertThat(registry.get("corpus.retrieval.top.score").gauge().value()).isZero();
        assertThat(registry.get("corpus.retrieval.top.score.observed").summary().count()).isEqualTo(1);
    }
}
