package dev.ahmeddyounis.corpus.ingestion;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingType;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TokenChunkerTest {

    private final Encoding encoding = Encodings.newDefaultEncodingRegistry()
            .getEncoding(EncodingType.CL100K_BASE);

    private String textOfTokens(int tokens) {
        StringBuilder sb = new StringBuilder();
        int word = 0;
        while (encoding.countTokens(sb.toString()) < tokens) {
            sb.append("word").append(word++).append(' ');
        }
        return sb.toString();
    }

    @Test
    void splitsIntoOverlappingWindows() {
        TokenChunker chunker = new TokenChunker(new CorpusChunkProperties(50, 10));
        String text = textOfTokens(120);

        List<TokenChunker.Chunk> chunks = chunker.chunk(text);

        assertThat(chunks).hasSizeGreaterThanOrEqualTo(3);
        assertThat(chunks.getFirst().tokenCount()).isEqualTo(50);
        assertThat(chunks.get(1).tokenCount()).isEqualTo(50);
        // Consecutive windows advance by size - overlap = 40 tokens.
        assertThat(chunks.getFirst().index()).isZero();
        assertThat(chunks.get(1).index()).isEqualTo(1);
        // Overlap: the tail of chunk N re-appears at the head of chunk N+1.
        String tailOfFirst = chunks.getFirst().text()
                .substring(chunks.getFirst().text().length() - 20);
        assertThat(chunks.get(1).text()).contains(tailOfFirst.strip());
    }

    @Test
    void shortTextYieldsSingleChunk() {
        TokenChunker chunker = new TokenChunker(new CorpusChunkProperties(512, 64));

        List<TokenChunker.Chunk> chunks = chunker.chunk("A short paragraph about nothing much.");

        assertThat(chunks).hasSize(1);
        assertThat(chunks.getFirst().text()).contains("short paragraph");
    }

    @Test
    void blankTextYieldsNoChunks() {
        TokenChunker chunker = new TokenChunker(new CorpusChunkProperties(512, 64));

        assertThat(chunker.chunk("   \n  ")).isEmpty();
    }

    @Test
    void rejectsOverlapNotSmallerThanSize() {
        assertThatThrownBy(() -> new CorpusChunkProperties(100, 100))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
