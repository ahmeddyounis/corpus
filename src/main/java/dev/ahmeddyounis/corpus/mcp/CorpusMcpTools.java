package dev.ahmeddyounis.corpus.mcp;

import dev.ahmeddyounis.corpus.chat.ChatService;
import dev.ahmeddyounis.corpus.ingestion.IngestionService;
import dev.ahmeddyounis.corpus.retrieval.RetrievalService;
import java.util.List;
import java.util.UUID;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

/**
 * The MCP tool surface: any MCP client (Claude Desktop, IDE agents) can search,
 * ask, and list over the user's corpus. Exposed over streamable HTTP at /mcp by
 * the Spring AI MCP server autoconfiguration's annotation scanner.
 */
@Component
public class CorpusMcpTools {

    public record SearchHit(String chunkId, String documentId, String filename, int chunkIndex,
                            int rank, double rrfScore, String content) {
    }

    public record CitationView(int index, String filename, int chunkIndex) {
    }

    public record AskAnswer(String answer, List<CitationView> citations, String conversationId) {
    }

    public record DocumentInfo(String id, String filename, String status, long sizeBytes,
                               int chunkCount, String error) {
    }

    private final RetrievalService retrievalService;
    private final ChatService chatService;
    private final IngestionService ingestionService;
    private final McpUserResolver userResolver;

    public CorpusMcpTools(RetrievalService retrievalService, ChatService chatService,
                          IngestionService ingestionService, McpUserResolver userResolver) {
        this.retrievalService = retrievalService;
        this.chatService = chatService;
        this.ingestionService = ingestionService;
        this.userResolver = userResolver;
    }

    @McpTool(name = "search_documents",
            description = "Search the user's document corpus with hybrid retrieval (keyword full-text "
                    + "+ vector similarity, fused with Reciprocal Rank Fusion). Returns ranked chunks "
                    + "with source filename, chunk index, and scores. Use this to find passages; use "
                    + "ask_documents for a synthesized answer.")
    public List<SearchHit> searchDocuments(
            @McpToolParam(description = "Natural-language or keyword query", required = true) String query,
            @McpToolParam(description = "Maximum chunks to return (default 6)", required = false) Integer topK) {
        UUID userId = userResolver.resolveUserId();
        return retrievalService.search(userId, query, topK, null).stream()
                .map(c -> new SearchHit(c.chunkId().toString(), c.documentId().toString(), c.filename(),
                        c.chunkIndex(), c.rank(), c.rrfScore(), c.content()))
                .toList();
    }

    @McpTool(name = "ask_documents",
            description = "Ask a natural-language question over the user's documents and get a "
                    + "citation-backed answer generated with retrieval-augmented generation.")
    public AskAnswer askDocuments(
            @McpToolParam(description = "The question to answer from the documents", required = true) String question,
            @McpToolParam(description = "How many context chunks to retrieve (default 6)", required = false)
            Integer topK) {
        UUID userId = userResolver.resolveUserId();
        ChatService.SyncAnswer answer = chatService.answerSync(userId, question, topK);
        return new AskAnswer(
                answer.answer(),
                answer.citations().stream()
                        .map(c -> new CitationView(c.index(), c.filename(), c.chunkIndex()))
                        .toList(),
                answer.conversationId().toString());
    }

    @McpTool(name = "list_documents",
            description = "List the user's uploaded documents with ingestion status and chunk counts.")
    public List<DocumentInfo> listDocuments() {
        UUID userId = userResolver.resolveUserId();
        return ingestionService.list(userId).stream()
                .map(d -> new DocumentInfo(d.id().toString(), d.filename(), d.status().name(),
                        d.sizeBytes(), d.chunkCount(), d.error()))
                .toList();
    }
}
