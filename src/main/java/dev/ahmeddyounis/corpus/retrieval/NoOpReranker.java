package dev.ahmeddyounis.corpus.retrieval;

import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Keeps fusion order and truncates to {@code topK} — the seam's inert default,
 * and the fallback whenever no model-backed reranker is configured.
 *
 * <p>Always registered. A model-backed reranker overrides it with {@code @Primary}
 * rather than this bean carrying {@code @ConditionalOnMissingBean}: that condition
 * is evaluated during component scanning, where the ordering between two scanned
 * beans is undefined, so it would work or not depending on scan order.
 *
 * <p>{@code isReady()} stays false: nothing here improves ranking, and the eval
 * harness asserts readiness so a build cannot pass believing it measured a
 * reranker it never loaded.
 */
@Component
public class NoOpReranker implements Reranker {

    @Override
    public List<ScoredChunk> rerank(String query, List<ScoredChunk> candidates, int topK) {
        return candidates.size() <= topK
                ? List.copyOf(candidates)
                : List.copyOf(candidates.subList(0, topK));
    }

    @Override
    public String name() {
        return "none";
    }
}
