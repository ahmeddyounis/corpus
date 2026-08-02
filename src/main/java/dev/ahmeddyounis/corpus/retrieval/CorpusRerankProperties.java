package dev.ahmeddyounis.corpus.retrieval;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Reranking knobs.
 *
 * <p>{@code enabled} is the per-request default that {@code /api/search}'s
 * {@code rerank} flag overrides, which is what makes a hand A/B possible against
 * a live index without a redeploy. {@code model.enabled} is separate and decides
 * whether the ONNX cross-encoder is loaded at all — a deployment can keep the
 * ~90 MB model out of the image's working set and still exercise the seam.
 *
 * @param concurrency how many rerank inferences may run at once. Cross-encoder
 *                    inference is CPU-bound JNI work, so this is a bulkhead: past
 *                    the limit reranking sheds and retrieval degrades to fusion
 *                    order rather than queueing behind a saturated CPU.
 *                    <p>It also divides the machine. ONNX Runtime parallelises a
 *                    single forward pass across cores, so {@code concurrency} and
 *                    the intra-op thread count trade against each other: this
 *                    default deliberately favours a small number of wide
 *                    inferences, because reranking sits on the request path and
 *                    latency there is worth more than peak throughput. Measured
 *                    on 14 cores, splitting the machine 7 ways instead made a
 *                    single rerank roughly 3x slower.
 * @param maxLength   sequence-pair token budget. The model's positional
 *                    embeddings stop at 512; longer pairs are truncated.
 */
@ConfigurationProperties(prefix = "corpus.rerank")
public record CorpusRerankProperties(boolean enabled, Model model, int concurrency, Duration timeout) {

    public record Model(boolean enabled, String modelUri, String tokenizerUri, String cacheDirectory,
                        int maxLength, int maxCandidates) {

        public Model {
            modelUri = hasText(modelUri) ? modelUri : DEFAULT_MODEL_URI;
            tokenizerUri = hasText(tokenizerUri) ? tokenizerUri : DEFAULT_TOKENIZER_URI;
            cacheDirectory = hasText(cacheDirectory) ? cacheDirectory : System.getProperty("java.io.tmpdir")
                    + "/corpus-onnx-cache";
            maxLength = maxLength > 0 ? Math.min(maxLength, 512) : 256;
            maxCandidates = maxCandidates > 0 ? maxCandidates : 40;
        }
    }

    /** cross-encoder/ms-marco-MiniLM-L-6-v2 — Apache-2.0, official ONNX export, single-logit output. */
    private static final String DEFAULT_MODEL_URI =
            "https://huggingface.co/cross-encoder/ms-marco-MiniLM-L-6-v2/resolve/main/onnx/model.onnx";
    private static final String DEFAULT_TOKENIZER_URI =
            "https://huggingface.co/cross-encoder/ms-marco-MiniLM-L-6-v2/resolve/main/tokenizer.json";

    public CorpusRerankProperties {
        model = model != null ? model : new Model(false, null, null, null, 0, 0);
        concurrency = concurrency > 0 ? concurrency : 2;
        timeout = timeout != null ? timeout : Duration.ofSeconds(2);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
