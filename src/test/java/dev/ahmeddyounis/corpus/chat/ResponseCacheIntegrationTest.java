package dev.ahmeddyounis.corpus.chat;

import dev.ahmeddyounis.corpus.security.UserAccount;
import dev.ahmeddyounis.corpus.security.UserRepository;
import dev.ahmeddyounis.corpus.support.AbstractIntegrationTest;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The correctness properties the response cache rests on: it must never serve
 * one user's answer to another, and it must stop serving answers the corpus has
 * outgrown.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ResponseCacheIntegrationTest extends AbstractIntegrationTest {

    private static final String QUESTION = "which vector index does corpus use";
    private static final String ANSWER = "HNSW with cosine distance [1]";

    @Autowired
    private ResponseCache cache;
    @Autowired
    private CorpusVersionDao versions;
    @Autowired
    private ResponseCacheDao dao;
    @Autowired
    private UserRepository users;
    @Autowired
    private PasswordEncoder passwordEncoder;

    private UUID alice;
    private UUID bob;

    @BeforeAll
    void createUsers() {
        alice = user("cache-alice");
        bob = user("cache-bob");
    }

    private UUID user(String username) {
        return users.findByUsername(username)
                .orElseGet(() -> users.save(UserAccount.create(username, passwordEncoder.encode("x"))))
                .id();
    }

    @Test
    void anAnswerIsServedBackToTheSameUser() {
        cache.put(alice, QUESTION, "test", ANSWER, List.of());

        var hit = cache.lookup(alice, QUESTION, "test", true);

        assertThat(hit).isPresent();
        assertThat(hit.get().answer()).isEqualTo(ANSWER);
        assertThat(hit.get().exact()).isTrue();
    }

    /**
     * The property that makes this cache safe to ship. Answers quote one user's
     * documents, so a cross-user hit would be a data leak, not a stale read.
     */
    @Test
    void anotherUserNeverSeesIt() {
        cache.put(alice, QUESTION, "test", ANSWER, List.of());

        assertThat(cache.lookup(bob, QUESTION, "test", true)).isEmpty();
    }

    /** A different chat model must not serve the previous model's output. */
    @Test
    void aDifferentModelKeyMisses() {
        cache.put(alice, "namespaced question", "model-a", ANSWER, List.of());

        assertThat(cache.lookup(alice, "namespaced question", "model-b", true)).isEmpty();
        assertThat(cache.lookup(alice, "namespaced question", "model-a", true)).isPresent();
    }

    /**
     * Invalidation is by stamp, not deletion: after the corpus changes, every
     * prior answer becomes unreachable at once without touching a row.
     */
    @Test
    void bumpingTheCorpusVersionStrandsEveryPriorAnswer() {
        cache.put(bob, "stale question", "test", ANSWER, List.of());
        assertThat(cache.lookup(bob, "stale question", "test", true)).isPresent();

        versions.bump(bob);

        assertThat(cache.lookup(bob, "stale question", "test", true)).isEmpty();
    }

    /** Case and spacing differences are the same question. */
    @Test
    void normalizationFoldsCaseAndWhitespaceOnTheExactPath() {
        cache.put(alice, "How  Does RRF Work?", "test", ANSWER, List.of());

        assertThat(cache.lookup(alice, "how does rrf work?", "test", true)).isPresent();
    }

    /**
     * A follow-up turn is meaningless without the turns before it, so it is never
     * answered from cache however closely it matches.
     */
    @Test
    void followUpTurnsAreNotServedFromCache() {
        cache.put(alice, "what about the second one", "test", ANSWER, List.of());

        assertThat(cache.lookup(alice, "what about the second one", "test", false)).isEmpty();
    }

    @Test
    void trimBoundsOneUsersPartitionWithoutTouchingAnother() {
        long aliceVersion = versions.current(alice);
        for (int i = 0; i < 6; i++) {
            cache.put(alice, "trim probe " + i, "trim", ANSWER, List.of());
        }
        cache.put(bob, "bob keeps this", "trim", ANSWER, List.of());
        long bobBefore = dao.size(bob);

        dao.trim(alice, aliceVersion, 2);

        assertThat(dao.size(bob)).isEqualTo(bobBefore);
    }
}
