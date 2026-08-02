package dev.ahmeddyounis.corpus.embedding;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

import static org.assertj.core.api.Assertions.assertThat;

class CachingEmbeddingModelTest {

    /** Records what actually reached the model, which is the whole point of the cache. */
    private static final class RecordingModel implements EmbeddingModel {
        final List<String> embedded = new ArrayList<>();
        int callCount;

        @Override
        public EmbeddingResponse call(EmbeddingRequest request) {
            callCount++;
            embedded.addAll(request.getInstructions());
            List<Embedding> results = new ArrayList<>();
            for (int i = 0; i < request.getInstructions().size(); i++) {
                results.add(new Embedding(vectorFor(request.getInstructions().get(i)), i));
            }
            return new EmbeddingResponse(results);
        }

        @Override
        public float[] embed(Document document) {
            return embed(document.getText());
        }

        @Override
        public int dimensions() {
            return 3;
        }

        static float[] vectorFor(String text) {
            return new float[] {text.length(), text.hashCode() % 100, 1.0f};
        }
    }

    /** In-memory stand-in for the shared tier, so this stays a unit test. */
    private static final class FakeDao extends EmbeddingCacheDao {
        final Map<String, float[]> rows = new HashMap<>();
        int lookups;

        FakeDao() {
            super(null);
        }

        @Override
        public Map<String, float[]> findAll(String namespace, List<String> hashes) {
            lookups++;
            Map<String, float[]> found = new HashMap<>();
            for (String hash : hashes) {
                float[] vector = rows.get(namespace + "|" + hash);
                if (vector != null) {
                    found.put(hash, vector);
                }
            }
            return found;
        }

        @Override
        public void put(String namespace, String hash, float[] embedding) {
            rows.put(namespace + "|" + hash, embedding);
        }

        @Override
        public long size(String namespace) {
            return rows.size();
        }

        @Override
        public int evictColdest(String namespace, int keep) {
            return 0;
        }
    }

    private final RecordingModel delegate = new RecordingModel();
    private final FakeDao dao = new FakeDao();
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    private CachingEmbeddingModel cache(String namespace) {
        return new CachingEmbeddingModel(delegate, dao, registry, namespace, 100, 1000);
    }

    @Test
    void secondCallForTheSameTextNeverReachesTheModel() {
        CachingEmbeddingModel model = cache("test:model:3");

        float[] first = model.embed("hybrid retrieval");
        float[] second = model.embed("hybrid retrieval");

        assertThat(first).containsExactly(second);
        assertThat(delegate.embedded).containsExactly("hybrid retrieval");
    }

    @Test
    void onlyTheUncachedMembersOfABatchReachTheModel() {
        CachingEmbeddingModel model = cache("test:model:3");
        model.embed("already seen");

        List<float[]> vectors = model.embed(List.of("already seen", "brand new", "also new"));

        assertThat(vectors).hasSize(3);
        assertThat(delegate.embedded).containsExactly("already seen", "brand new", "also new");
        assertThat(delegate.callCount).isEqualTo(2);
    }

    /**
     * Results are returned in request order regardless of which tier served them.
     * A caller pairing vectors back to its own inputs by position must not be able
     * to tell a cache hit from a miss.
     */
    @Test
    void resultsStayInRequestOrderWhenTiersAreMixed() {
        CachingEmbeddingModel model = cache("test:model:3");
        model.embed("warm");

        List<String> inputs = List.of("cold one", "warm", "cold two");
        List<float[]> vectors = model.embed(inputs);

        for (int i = 0; i < inputs.size(); i++) {
            assertThat(vectors.get(i)).as("position %d", i)
                    .containsExactly(RecordingModel.vectorFor(inputs.get(i)));
        }
        EmbeddingResponse response = model.call(new EmbeddingRequest(inputs, null));
        assertThat(response.getResults()).extracting(Embedding::getIndex).containsExactly(0, 1, 2);
    }

    @Test
    void repeatedTextWithinOneBatchIsEmbeddedOnce() {
        CachingEmbeddingModel model = cache("test:model:3");

        List<float[]> vectors = model.embed(List.of("same", "same", "same"));

        assertThat(vectors).hasSize(3);
        assertThat(delegate.embedded).containsExactly("same");
    }

    /**
     * The failure mode that makes an embedding cache dangerous. A model or
     * dimension change must miss, not return a vector the current model would
     * never have produced.
     */
    @Test
    void aDifferentModelNamespaceCannotSeeTheFirstModelsVectors() {
        cache("openai:text-embedding-3-small:1536").embed("shared text");
        int afterFirst = delegate.callCount;

        cache("transformers:all-MiniLM-L6-v2:384").embed("shared text");

        assertThat(delegate.callCount).as("second namespace must miss").isEqualTo(afterFirst + 1);
    }

    /** A cold replica reads vectors another replica computed. */
    @Test
    void aSecondReplicaHitsTheSharedTierWithoutCallingTheModel() {
        cache("test:model:3").embed("written by replica one");
        int afterFirst = delegate.callCount;

        CachingEmbeddingModel replicaTwo = cache("test:model:3");
        replicaTwo.embed("written by replica one");

        assertThat(delegate.callCount).isEqualTo(afterFirst);
        assertThat(registry.get("corpus.embedding.cache").tag("tier", "l2").counter().count())
                .isEqualTo(1.0);
    }

    @Test
    void tiersAndMissesAreCounted() {
        CachingEmbeddingModel model = cache("test:model:3");
        model.embed("x");
        model.embed("x");

        assertThat(registry.get("corpus.embedding.cache").tag("result", "miss").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("corpus.embedding.cache").tag("tier", "l1").counter().count()).isEqualTo(1.0);
    }

    @Test
    void hashingIsStableAndTextIsNeverStored() {
        CachingEmbeddingModel model = cache("test:model:3");
        model.embed("secret document content");

        assertThat(CachingEmbeddingModel.sha256("secret document content"))
                .isEqualTo(CachingEmbeddingModel.sha256("secret document content"))
                .hasSize(64);
        assertThat(dao.rows.keySet()).allSatisfy(key ->
                assertThat(key).doesNotContain("secret document content"));
    }

    @Test
    void dimensionsAreReportedByTheDelegate() {
        assertThat(cache("test:model:3").dimensions()).isEqualTo(3);
        assertThat(cache("ns").namespace()).isEqualTo("ns");
    }
}
