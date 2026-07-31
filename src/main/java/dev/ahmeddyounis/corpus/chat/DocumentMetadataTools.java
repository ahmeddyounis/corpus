package dev.ahmeddyounis.corpus.chat;

import dev.ahmeddyounis.corpus.ingestion.DocumentEntity;
import dev.ahmeddyounis.corpus.ingestion.DocumentRepository;
import java.util.UUID;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

/**
 * Tool the chat model can call to answer questions about the documents themselves
 * (Spring AI function calling). The acting user is passed via ToolContext, never
 * chosen by the model.
 */
@Component
public class DocumentMetadataTools {

    public static final String USER_ID_CONTEXT_KEY = "corpus_user_id";

    private final DocumentRepository documents;

    public DocumentMetadataTools(DocumentRepository documents) {
        this.documents = documents;
    }

    @Tool(description = "Look up ingestion metadata (status, size in bytes, chunk count, upload time) "
            + "for one of the user's documents by its exact filename")
    public String documentInfo(String filename, ToolContext toolContext) {
        UUID userId = UUID.fromString(String.valueOf(toolContext.getContext().get(USER_ID_CONTEXT_KEY)));
        return documents.findByUserIdAndFilename(userId, filename)
                .map(DocumentMetadataTools::describe)
                .orElse("No document named '" + filename + "' found for this user.");
    }

    private static String describe(DocumentEntity doc) {
        return "Document '%s': status=%s, size=%d bytes, chunks=%d, uploaded=%s%s".formatted(
                doc.filename(), doc.status(), doc.sizeBytes(), doc.chunkCount(), doc.createdAt(),
                doc.error() != null ? ", error=" + doc.error() : "");
    }
}
