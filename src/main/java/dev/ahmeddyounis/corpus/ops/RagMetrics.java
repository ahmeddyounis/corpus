package dev.ahmeddyounis.corpus.ops;

import dev.ahmeddyounis.corpus.retrieval.ScoredChunk;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Component;

/**
 * LLM/RAG observability: per-phase latency timers (histogram buckets for p95
 * panels), token and estimated-cost counters, and retrieval-quality signals
 * (last top score / score spread gauges + a top-score distribution summary).
 */
@Component
public class RagMetrics {

    private final MeterRegistry registry;
    private final AtomicReference<Double> topScore = new AtomicReference<>(0.0);
    private final AtomicReference<Double> scoreSpread = new AtomicReference<>(0.0);
    private final DistributionSummary topScoreSummary;

    public RagMetrics(MeterRegistry registry) {
        this.registry = registry;
        registry.gauge("corpus.retrieval.top.score", topScore, r -> r.get());
        registry.gauge("corpus.retrieval.score.spread", scoreSpread, r -> r.get());
        this.topScoreSummary = DistributionSummary.builder("corpus.retrieval.top.score.observed")
                .description("Distribution of RRF top scores per retrieval")
                .register(registry);
    }

    public void recordPhase(String phase, long millis) {
        Timer.builder("corpus.rag.phase")
                .description("RAG pipeline phase latency")
                .tag("phase", phase)
                .publishPercentileHistogram()
                .register(registry)
                .record(Duration.ofMillis(Math.max(0, millis)));
    }

    public void recordTokens(String provider, String model, Integer promptTokens, Integer completionTokens) {
        if (promptTokens != null && promptTokens > 0) {
            tokenCounter(provider, model, "input").increment(promptTokens);
        }
        if (completionTokens != null && completionTokens > 0) {
            tokenCounter(provider, model, "output").increment(completionTokens);
        }
    }

    public void recordCost(String provider, String model, double usd) {
        Counter.builder("corpus.llm.cost.estimate")
                .description("Estimated LLM spend in USD from the configured price table")
                .baseUnit("usd")
                .tag("provider", provider)
                .tag("model", model)
                .register(registry)
                .increment(usd);
    }

    public void recordRetrieval(List<ScoredChunk> chunks) {
        if (chunks.isEmpty()) {
            topScore.set(0.0);
            scoreSpread.set(0.0);
            return;
        }
        double top = chunks.getFirst().rrfScore();
        double last = chunks.getLast().rrfScore();
        topScore.set(top);
        scoreSpread.set(top - last);
        topScoreSummary.record(top);
    }

    private Counter tokenCounter(String provider, String model, String direction) {
        return Counter.builder("corpus.llm.tokens")
                .description("LLM token usage")
                .tag("provider", provider)
                .tag("model", model)
                .tag("direction", direction)
                .register(registry);
    }
}
