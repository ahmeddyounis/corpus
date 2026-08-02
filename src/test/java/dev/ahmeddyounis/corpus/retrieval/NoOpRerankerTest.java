package dev.ahmeddyounis.corpus.retrieval;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class NoOpRerankerTest {

    private final NoOpReranker reranker = new NoOpReranker();

    private static ScoredChunk chunk(int rank, double rrf) {
        return new ScoredChunk(UUID.randomUUID(), UUID.randomUUID(), "doc-" + rank + ".md", 0, "text",
                rank, rrf, null, null, null);
    }

    private static List<ScoredChunk> candidates(int n) {
        return java.util.stream.IntStream.rangeClosed(1, n)
                .mapToObj(i -> chunk(i, 1.0 / i))
                .toList();
    }

    @Test
    void keepsFusionOrderAndTruncatesToTopK() {
        List<ScoredChunk> input = candidates(20);

        List<ScoredChunk> out = reranker.rerank("anything", input, 6);

        assertThat(out).hasSize(6);
        assertThat(out).extracting(ScoredChunk::filename)
                .containsExactlyElementsOf(input.subList(0, 6).stream().map(ScoredChunk::filename).toList());
        assertThat(out).extracting(ScoredChunk::rank).containsExactly(1, 2, 3, 4, 5, 6);
    }

    @Test
    void returnsEverythingWhenFewerCandidatesThanTopK() {
        assertThat(reranker.rerank("q", candidates(3), 6)).hasSize(3);
        assertThat(reranker.rerank("q", List.of(), 6)).isEmpty();
    }

    /**
     * The eval harness gates on {@code isReady()} so that a fail-open reranker that
     * silently degraded to fusion order cannot produce a green "reranking works" build.
     */
    @Test
    void reportsItselfAsNotReady() {
        assertThat(reranker.isReady()).isFalse();
        assertThat(reranker.name()).isEqualTo("none");
    }

    @Test
    void renumberRewritesRanksWithoutTouchingScores() {
        List<ScoredChunk> shuffled = List.of(chunk(7, 0.5), chunk(2, 0.9), chunk(4, 0.1));

        List<ScoredChunk> out = Reranker.renumber(shuffled);

        assertThat(out).extracting(ScoredChunk::rank).containsExactly(1, 2, 3);
        assertThat(out).extracting(ScoredChunk::rrfScore).containsExactly(0.5, 0.9, 0.1);
        assertThat(out).extracting(ScoredChunk::filename)
                .containsExactly("doc-7.md", "doc-2.md", "doc-4.md");
    }
}
