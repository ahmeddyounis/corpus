package dev.ahmeddyounis.corpus.ingestion;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingType;
import com.knuddels.jtokkit.api.IntArrayList;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Token-aware sliding-window chunker (cl100k_base): windows of {@code size} tokens
 * advancing by {@code size - overlap}, so consecutive chunks share {@code overlap}
 * tokens of context. Spring AI's TokenTextSplitter has no overlap parameter, which
 * is why this is hand-rolled.
 */
@Component
public class TokenChunker {

    public record Chunk(int index, String text, int tokenCount) {
    }

    private final Encoding encoding = Encodings.newDefaultEncodingRegistry().getEncoding(EncodingType.CL100K_BASE);
    private final int size;
    private final int overlap;

    public TokenChunker(CorpusChunkProperties properties) {
        this.size = properties.size();
        this.overlap = properties.overlap();
    }

    public List<Chunk> chunk(String text) {
        IntArrayList tokens = encoding.encode(text);
        List<Chunk> chunks = new ArrayList<>();
        int stride = size - overlap;
        int index = 0;
        for (int start = 0; start < tokens.size(); start += stride) {
            int end = Math.min(start + size, tokens.size());
            IntArrayList window = new IntArrayList(end - start);
            for (int i = start; i < end; i++) {
                window.add(tokens.get(i));
            }
            String chunkText = encoding.decode(window).strip();
            if (!chunkText.isEmpty()) {
                chunks.add(new Chunk(index++, chunkText, end - start));
            }
            if (end == tokens.size()) {
                break;
            }
        }
        return chunks;
    }
}
