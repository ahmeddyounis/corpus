package dev.ahmeddyounis.corpus.embedding;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

/**
 * Content-addressed cache in front of whatever embedding model the active
 * profile configured.
 *
 * <p>An embedding is a pure function of {@code (text, provider, model,
 * dimension)}, which makes it the one derived artifact in this service that is
 * safely shared across users: the cache stores a SHA-256 of the text and the
 * resulting vector, never the text itself, so nothing in it can reconstruct
 * another user's document. See ADR 0011.
 *
 * <p>The namespace carries {@code provider:model:dimension}. A model swap or a
 * dimension change therefore produces cache <em>misses</em> rather than
 * plausible-looking vectors from the wrong model — the failure mode that makes
 * an embedding cache dangerous is structurally impossible instead of guarded by
 * a convention someone has to remember.
 *
 * <p>Two tiers: a bounded in-process LRU that costs no network, and a Postgres
 * table shared by every replica. Both are optimisations, so every failure path
 * falls through to the real model.
 */
public class CachingEmbeddingModel implements EmbeddingModel {

    private final EmbeddingModel delegate;
    private final EmbeddingCacheDao dao;
    private final MeterRegistry registry;
    private final String namespace;
    private final int l2MaxEntries;
    private final Map<String, float[]> l1;
    private final AtomicLong writesSinceEviction = new AtomicLong();

    public CachingEmbeddingModel(EmbeddingModel delegate, EmbeddingCacheDao dao, MeterRegistry registry,
                                 String namespace, int l1MaxEntries, int l2MaxEntries) {
        this.delegate = delegate;
        this.dao = dao;
        this.registry = registry;
        this.namespace = namespace;
        this.l2MaxEntries = l2MaxEntries;
        this.l1 = Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, float[]> eldest) {
                return size() > l1MaxEntries;
            }
        });
    }

    public String namespace() {
        return namespace;
    }

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        List<String> texts = request.getInstructions();
        if (texts.isEmpty()) {
            return delegate.call(request);
        }

        List<String> hashes = texts.stream().map(CachingEmbeddingModel::sha256).toList();
        Map<String, float[]> resolved = new HashMap<>(texts.size());

        List<String> l1Misses = new ArrayList<>();
        for (String hash : hashes) {
            float[] cached = l1.get(hash);
            if (cached != null) {
                resolved.put(hash, cached);
                hit("l1");
            } else if (!resolved.containsKey(hash)) {
                l1Misses.add(hash);
            }
        }

        if (!l1Misses.isEmpty()) {
            Map<String, float[]> fromL2 = dao.findAll(namespace, l1Misses.stream().distinct().toList());
            fromL2.forEach((hash, vector) -> {
                resolved.put(hash, vector);
                l1.put(hash, vector);
                hit("l2");
            });
        }

        // Only the texts nothing had reach the real model, deduplicated: a batch
        // with the same chunk twice should cost one embedding, not two.
        List<String> toEmbed = new ArrayList<>();
        List<String> toEmbedHashes = new ArrayList<>();
        for (int i = 0; i < texts.size(); i++) {
            String hash = hashes.get(i);
            if (!resolved.containsKey(hash) && !toEmbedHashes.contains(hash)) {
                toEmbed.add(texts.get(i));
                toEmbedHashes.add(hash);
            }
        }

        EmbeddingResponse fresh = null;
        if (!toEmbed.isEmpty()) {
            fresh = delegate.call(new EmbeddingRequest(toEmbed, request.getOptions()));
            List<Embedding> results = fresh.getResults();
            for (int i = 0; i < results.size() && i < toEmbedHashes.size(); i++) {
                float[] vector = results.get(i).getOutput();
                String hash = toEmbedHashes.get(i);
                resolved.put(hash, vector);
                l1.put(hash, vector);
                dao.put(namespace, hash, vector);
                miss();
            }
            maybeEvict(results.size());
        }

        // Rebuilt in request order with per-position indexes, because a caller
        // pairing results back to its own inputs by position must not be able to
        // tell which of them came from the cache.
        List<Embedding> ordered = new ArrayList<>(texts.size());
        for (int i = 0; i < texts.size(); i++) {
            float[] vector = resolved.get(hashes.get(i));
            if (vector == null) {
                // The delegate returned fewer results than inputs. Falling back
                // wholesale beats returning a response with a hole in it.
                return delegate.call(request);
            }
            ordered.add(new Embedding(vector, i));
        }
        return fresh != null ? new EmbeddingResponse(ordered, fresh.getMetadata())
                : new EmbeddingResponse(ordered);
    }

    @Override
    public float[] embed(Document document) {
        return embed(document.getText());
    }

    @Override
    public int dimensions() {
        return delegate.dimensions();
    }

    /**
     * Bounds the shared tier. Checked every {@code l2MaxEntries / 4} writes rather
     * than on each one, so the common path stays a single insert.
     */
    private void maybeEvict(int written) {
        long total = writesSinceEviction.addAndGet(written);
        int checkEvery = Math.max(1, l2MaxEntries / 4);
        if (total < checkEvery) {
            return;
        }
        writesSinceEviction.set(0);
        if (dao.size(namespace) > l2MaxEntries) {
            int evicted = dao.evictColdest(namespace, l2MaxEntries);
            Counter.builder("corpus.embedding.cache.evictions")
                    .description("Embedding cache entries evicted from the shared tier")
                    .register(registry)
                    .increment(evicted);
        }
    }

    private void hit(String tier) {
        counter("hit", tier).increment();
    }

    private void miss() {
        counter("miss", "none").increment();
    }

    private Counter counter(String result, String tier) {
        return Counter.builder("corpus.embedding.cache")
                .description("Embedding cache lookups by outcome")
                .tag("result", result)
                .tag("tier", tier)
                .register(registry);
    }

    static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
