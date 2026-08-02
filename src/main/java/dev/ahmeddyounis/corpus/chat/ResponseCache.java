package dev.ahmeddyounis.corpus.chat;

import dev.ahmeddyounis.corpus.embedding.CachingEmbeddingModel;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.StringJoiner;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Service;

/**
 * Per-user semantic cache over generated answers.
 *
 * <p>An answer is derived from one user's documents and quotes them directly, so
 * unlike the embedding cache this one is <em>never</em> shared. Isolation is
 * structural: the row cascades with the user and every statement carries a
 * {@code user_id} predicate.
 *
 * <p>Lookup is two-phase. An exact match on the normalized question needs no
 * embedding call at all — the common "ask the same thing again" case is a single
 * indexed read. Only if that misses is the question embedded and compared by
 * cosine similarity, and that embedding itself goes through the embedding cache.
 *
 * <p>Invalidation is by {@code corpus_version} rather than deletion: an upload or
 * delete bumps the stamp and every prior entry becomes unreachable at once.
 * Entries written concurrently with an ingest are born stale, which is the
 * correct outcome and needs no coordination.
 */
@Service
public class ResponseCache {

    private static final Logger log = LoggerFactory.getLogger(ResponseCache.class);

    public record Hit(String answer, List<Citation> citations, double similarity, boolean exact) {
    }

    private final ResponseCacheDao dao;
    private final CorpusVersionDao versions;
    private final EmbeddingModel embeddingModel;
    private final CorpusResponseCacheProperties properties;
    private final MeterRegistry registry;

    public ResponseCache(ResponseCacheDao dao, CorpusVersionDao versions, EmbeddingModel embeddingModel,
                         CorpusResponseCacheProperties properties, MeterRegistry registry) {
        this.dao = dao;
        this.versions = versions;
        this.embeddingModel = embeddingModel;
        this.properties = properties;
        this.registry = registry;
    }

    public boolean enabled() {
        return properties.enabled();
    }

    /**
     * @param firstTurn whether this is the opening turn of a conversation. A
     *                  cached answer is only sound for a self-contained question;
     *                  "what about the second one?" means nothing without the
     *                  turns before it, so by default only first turns are served
     *                  from cache.
     */
    public Optional<Hit> lookup(UUID userId, String question, String modelKey, boolean firstTurn) {
        if (!properties.enabled() || (properties.firstTurnOnly() && !firstTurn)) {
            return Optional.empty();
        }
        try {
            long version = versions.current(userId);
            String hash = CachingEmbeddingModel.sha256(normalize(question));

            Optional<ResponseCacheDao.CachedAnswer> exact =
                    dao.findExact(userId, modelKey, version, hash);
            if (exact.isPresent()) {
                return Optional.of(serve(userId, exact.get(), true));
            }

            String embedding = literal(embeddingModel.embed(question));
            Optional<ResponseCacheDao.CachedAnswer> similar =
                    dao.findSimilar(userId, modelKey, version, embedding, properties.similarityThreshold());
            if (similar.isPresent()) {
                return Optional.of(serve(userId, similar.get(), false));
            }
            outcome("miss").increment();
            return Optional.empty();
        } catch (Exception e) {
            // A cache lookup must never fail a chat request.
            log.debug("Response cache lookup failed, answering normally: {}", e.toString());
            outcome("error").increment();
            return Optional.empty();
        }
    }

    private Hit serve(UUID userId, ResponseCacheDao.CachedAnswer cached, boolean exact) {
        dao.recordHit(userId, cached.id());
        outcome(exact ? "hit-exact" : "hit-semantic").increment();
        return new Hit(cached.answer(), cached.citations(), cached.similarity(), exact);
    }

    public void put(UUID userId, String question, String modelKey, String answer, List<Citation> citations) {
        if (!properties.enabled() || answer == null || answer.isBlank()) {
            return;
        }
        try {
            long version = versions.current(userId);
            dao.put(userId, modelKey, version, CachingEmbeddingModel.sha256(normalize(question)),
                    question, answer, citations, literal(embeddingModel.embed(question)));
            if (dao.size(userId) > properties.maxEntriesPerUser()) {
                dao.trim(userId, version, properties.maxEntriesPerUser());
            }
        } catch (Exception e) {
            log.debug("Response cache write failed: {}", e.toString());
        }
    }

    /**
     * Case and whitespace only. Anything more aggressive — stripping punctuation,
     * stemming — would collide questions that differ in meaning, and the semantic
     * phase already covers genuine paraphrases.
     */
    public static String normalize(String question) {
        return question.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    private static String literal(float[] vector) {
        StringJoiner joiner = new StringJoiner(",", "[", "]");
        for (float value : vector) {
            joiner.add(Float.toString(value));
        }
        return joiner.toString();
    }

    private Counter outcome(String result) {
        return Counter.builder("corpus.response.cache")
                .description("Semantic response cache lookups by outcome")
                .tag("result", result)
                .register(registry);
    }
}
