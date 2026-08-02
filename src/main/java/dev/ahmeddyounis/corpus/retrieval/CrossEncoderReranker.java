package dev.ahmeddyounis.corpus.retrieval;

import ai.djl.huggingface.tokenizers.Encoding;
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.djl.util.PairList;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.transformers.ResourceCacheService;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * In-process cross-encoder reranking with {@code ms-marco-MiniLM-L-6-v2}.
 *
 * <p>A bi-encoder embeds the query and the passage separately, so their vectors
 * are compared without either having seen the other. A cross-encoder runs the
 * concatenated pair through the transformer and emits a single relevance logit,
 * which is why it can separate a passage that merely shares vocabulary with the
 * query from one that answers it. That costs one forward pass per candidate,
 * which is affordable only over a fused shortlist — never over the corpus.
 *
 * <p>Everything here is keyless and in-process: the model is an Apache-2.0 ONNX
 * export fetched once through Spring AI's {@link ResourceCacheService}, into the
 * same cache directory the embedding model already uses.
 *
 * <h2>Failure posture</h2>
 * Every failure path degrades to fusion order rather than propagating: a missing
 * model at startup, an inference error, a timeout, or a shed request under load.
 * Ranking quality is a spectrum; retrieval throwing is an outage.
 *
 * <p>The corresponding hazard is a reranker that is silently never running, so
 * {@link #isReady()} reports load state and the eval harness gates on it.
 */
@Component
@Primary
@ConditionalOnProperty(prefix = "corpus.rerank.model", name = "enabled", havingValue = "true")
public class CrossEncoderReranker implements Reranker, InitializingBean, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(CrossEncoderReranker.class);

    private static final String INPUT_IDS = "input_ids";
    private static final String ATTENTION_MASK = "attention_mask";
    private static final String TOKEN_TYPE_IDS = "token_type_ids";
    private static final String LOGITS = "logits";

    private final CorpusRerankProperties.Model properties;
    private final Duration timeout;
    private final Reranker fallback;
    private final MeterRegistry registry;
    private final ExecutorService inferenceExecutor;
    private final int intraOpThreads;
    private final AtomicBoolean ready = new AtomicBoolean(false);

    private OrtEnvironment environment;
    private OrtSession session;
    private HuggingFaceTokenizer tokenizer;

    public CrossEncoderReranker(CorpusRerankProperties properties, NoOpReranker fallback, MeterRegistry registry) {
        this.properties = properties.model();
        this.timeout = properties.timeout();
        this.fallback = fallback;
        this.registry = registry;
        // Platform threads, not virtual: ONNX inference is CPU-bound native work that
        // would pin a carrier for its whole duration. A SynchronousQueue with the
        // default abort policy means saturation shows up immediately as a shed
        // rerank rather than as unbounded queueing behind a busy CPU.
        this.intraOpThreads = Math.max(1,
                Runtime.getRuntime().availableProcessors() / properties.concurrency());
        this.inferenceExecutor = new ThreadPoolExecutor(1, properties.concurrency(),
                60, TimeUnit.SECONDS, new SynchronousQueue<>(),
                runnable -> {
                    Thread thread = new Thread(runnable, "corpus-rerank");
                    thread.setDaemon(true);
                    return thread;
                });
    }

    @Override
    public void afterPropertiesSet() {
        try {
            ResourceCacheService cache = new ResourceCacheService(properties.cacheDirectory());
            Path modelPath = cache.getCachedResource(properties.modelUri()).getFile().toPath();
            Path tokenizerPath = cache.getCachedResource(properties.tokenizerUri()).getFile().toPath();

            this.environment = OrtEnvironment.getEnvironment();
            this.session = environment.createSession(modelPath.toString(), sessionOptions());
            validateGraph(session);

            this.tokenizer = HuggingFaceTokenizer.builder()
                    .optTokenizerPath(tokenizerPath)
                    .optMaxLength(properties.maxLength())
                    .optTruncation(true)
                    // Only the passage is truncated; a clipped question would change
                    // the very thing being scored.
                    .optTruncateSecondOnly()
                    .optPadding(true)
                    .build();

            ready.set(true);
            log.info("Cross-encoder reranker ready: model={} maxLength={}",
                    properties.modelUri(), properties.maxLength());
        } catch (Exception e) {
            // Deliberately not fatal. A reranker that cannot load is a quality
            // regression; refusing to start is an outage.
            log.warn("Cross-encoder reranker unavailable, falling back to fusion order: {}", e.toString());
            failures("load").increment();
        }
    }

    /**
     * ONNX Runtime defaults intra-op parallelism to every available core. With the
     * bulkhead permitting several concurrent reranks, each session would then try to
     * own the whole machine and they would fight each other; sizing intra-op threads
     * so {@code concurrency x intraOp} lands near the core count keeps the bulkhead
     * meaningful rather than decorative.
     */
    private OrtSession.SessionOptions sessionOptions() throws Exception {
        OrtSession.SessionOptions options = new OrtSession.SessionOptions();
        options.setIntraOpNumThreads(intraOpThreads);
        options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
        return options;
    }

    /**
     * Fails fast with the graph's real node names. An ONNX export that renames or
     * drops an input otherwise surfaces as a confusing per-request error much later.
     */
    private static void validateGraph(OrtSession session) throws Exception {
        Set<String> inputs = session.getInputNames();
        Set<String> outputs = session.getOutputNames();
        if (!inputs.containsAll(List.of(INPUT_IDS, ATTENTION_MASK, TOKEN_TYPE_IDS))
                || !outputs.contains(LOGITS)) {
            throw new IllegalStateException("Unexpected cross-encoder graph. inputs=%s outputs=%s"
                    .formatted(inputs, outputs));
        }
    }

    @Override
    public boolean isReady() {
        return ready.get();
    }

    @Override
    public String name() {
        return ready.get() ? "ms-marco-MiniLM-L-6-v2" : fallback.name();
    }

    @Override
    public List<ScoredChunk> rerank(String query, List<ScoredChunk> candidates, int topK) {
        // Only emptiness short-circuits. Skipping a single candidate would save one
        // cheap forward pass at the cost of a response whose rerankScore is
        // sometimes present and sometimes null for the same request shape.
        if (!ready.get() || candidates.isEmpty()) {
            return fallback.rerank(query, candidates, topK);
        }
        // Reranking a shortlist is the whole point; an unbounded candidate set would
        // put an unbounded number of forward passes on the request path.
        List<ScoredChunk> shortlist = candidates.size() > properties.maxCandidates()
                ? candidates.subList(0, properties.maxCandidates())
                : candidates;

        Future<float[]> inference;
        try {
            inference = inferenceExecutor.submit(() -> score(query, shortlist));
        } catch (RejectedExecutionException e) {
            failures("shed").increment();
            return fallback.rerank(query, candidates, topK);
        }
        try {
            return order(shortlist, inference.get(timeout.toMillis(), TimeUnit.MILLISECONDS), topK);
        } catch (TimeoutException e) {
            inference.cancel(true);
            failures("timeout").increment();
        } catch (InterruptedException e) {
            inference.cancel(true);
            Thread.currentThread().interrupt();
            failures("interrupted").increment();
        } catch (Exception e) {
            log.warn("Rerank failed, falling back to fusion order: {}", e.toString());
            failures("inference").increment();
        }
        return fallback.rerank(query, candidates, topK);
    }

    /** One batched forward pass over the (query, passage) pairs. */
    private float[] score(String query, List<ScoredChunk> shortlist) throws Exception {
        PairList<String, String> pairs = new PairList<>(shortlist.size());
        for (ScoredChunk chunk : shortlist) {
            pairs.add(query, chunk.content());
        }
        Encoding[] encodings = tokenizer.batchEncode(pairs);

        int batch = encodings.length;
        int length = encodings[0].getIds().length;
        long[][] ids = new long[batch][];
        long[][] mask = new long[batch][];
        long[][] types = new long[batch][];
        for (int i = 0; i < batch; i++) {
            ids[i] = encodings[i].getIds();
            mask[i] = encodings[i].getAttentionMask();
            types[i] = encodings[i].getTypeIds();
        }

        try (OnnxTensor idsTensor = OnnxTensor.createTensor(environment, ids);
             OnnxTensor maskTensor = OnnxTensor.createTensor(environment, mask);
             OnnxTensor typeTensor = OnnxTensor.createTensor(environment, types)) {
            Map<String, OnnxTensor> inputs = new HashMap<>(3);
            inputs.put(INPUT_IDS, idsTensor);
            inputs.put(ATTENTION_MASK, maskTensor);
            inputs.put(TOKEN_TYPE_IDS, typeTensor);
            try (OrtSession.Result result = session.run(inputs)) {
                float[][] logits = (float[][]) result.get(LOGITS).orElseThrow().getValue();
                float[] scores = new float[batch];
                for (int i = 0; i < batch; i++) {
                    // Single-label regression head: the raw logit is the score, and only
                    // its ordering matters. Squashing it would discard nothing but
                    // would hide how far apart the model placed two passages.
                    scores[i] = logits[i][0];
                }
                log.debug("Reranked {} candidates at sequence length {}", batch, length);
                return scores;
            }
        }
    }

    private static List<ScoredChunk> order(List<ScoredChunk> shortlist, float[] scores, int topK) {
        List<ScoredChunk> scored = new ArrayList<>(shortlist.size());
        for (int i = 0; i < shortlist.size(); i++) {
            scored.add(shortlist.get(i).withRerankScore(scores[i]));
        }
        scored.sort(Comparator.comparingDouble(ScoredChunk::rerankScore).reversed());
        return Reranker.renumber(scored.size() > topK ? scored.subList(0, topK) : scored);
    }

    private Counter failures(String reason) {
        return Counter.builder("corpus.rerank.failures")
                .description("Reranks that fell back to fusion order, by reason")
                .tag("reason", reason)
                .register(registry);
    }

    @Override
    public void destroy() {
        ready.set(false);
        inferenceExecutor.shutdownNow();
        closeQuietly(session);
        closeQuietly(tokenizer);
    }

    private static void closeQuietly(AutoCloseable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Exception e) {
            log.debug("Ignoring close failure for {}: {}", closeable.getClass().getSimpleName(), e.toString());
        }
    }
}
