package dev.ahmeddyounis.corpus.chat;

import dev.ahmeddyounis.corpus.retrieval.ScoredChunk;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class RagPromptBuilder {

    public static final String SYSTEM_PROMPT = """
            You are Corpus, an assistant that answers questions strictly from the user's documents.
            The user message contains numbered context chunks [1]..[N] retrieved from their corpus.
            Rules:
            - Ground every claim in the context and cite supporting chunk numbers inline, like [1] or [2][3].
            - If the context does not contain the answer, say you don't know and name what is missing;
              never fabricate content or citations.
            - You may call the available tools to look up document metadata when the user asks about
              their documents themselves (status, size, chunk counts).
            - Be concise.
            """;

    public String contextBlock(List<ScoredChunk> chunks) {
        if (chunks.isEmpty()) {
            return "(no relevant context found)";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < chunks.size(); i++) {
            ScoredChunk chunk = chunks.get(i);
            sb.append('[').append(i + 1).append("] ")
                    .append(chunk.filename()).append("#chunk").append(chunk.chunkIndex()).append('\n')
                    .append(chunk.content()).append("\n\n");
        }
        return sb.toString().stripTrailing();
    }

    public String userMessage(String question, String contextBlock) {
        return "Context:\n\n" + contextBlock + "\n\nQuestion: " + question;
    }
}
