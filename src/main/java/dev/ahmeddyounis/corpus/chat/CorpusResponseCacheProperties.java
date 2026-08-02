package dev.ahmeddyounis.corpus.chat;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Semantic response cache tuning.
 *
 * @param similarityThreshold cosine similarity above which a stored question is
 *                            treated as the same question. Measured, not guessed:
 *                            {@code SemanticCacheEvalTest} scores every pair of
 *                            golden questions and reports the margin between the
 *                            closest unrelated pair and the nearest paraphrase.
 * @param firstTurnOnly       serve from cache only on a conversation's opening
 *                            turn. A follow-up like "what about the second one?"
 *                            is meaningless without the turns before it, so a
 *                            cached answer to it would be wrong however similar
 *                            the wording.
 */
@ConfigurationProperties(prefix = "corpus.chat.response-cache")
public record CorpusResponseCacheProperties(boolean enabled, double similarityThreshold,
                                            boolean firstTurnOnly, int maxEntriesPerUser) {

    public CorpusResponseCacheProperties {
        // Measured with all-MiniLM-L6-v2: the closest unrelated pair of golden
        // questions scores 0.547 over 461 comparisons, the weakest hand-written
        // paraphrase 0.761. 0.72 sits well clear of the first - the side where
        // being wrong means answering with another question's answer - while
        // still catching genuine rephrasings. The scale is model-specific, which
        // is one more reason model_key namespaces the cache.
        similarityThreshold = similarityThreshold > 0 ? similarityThreshold : 0.72;
        maxEntriesPerUser = maxEntriesPerUser > 0 ? maxEntriesPerUser : 500;
    }
}
