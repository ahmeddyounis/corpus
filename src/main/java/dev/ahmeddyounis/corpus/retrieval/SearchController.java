package dev.ahmeddyounis.corpus.retrieval;

import dev.ahmeddyounis.corpus.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/search")
public class SearchController {

    public record SearchRequestBody(@NotBlank @Size(max = 1000) String query,
                                    @Min(1) @Max(20) Integer topK,
                                    @Size(max = 50) List<UUID> documentIds) {
    }

    public record SearchResponse(String query, int count, List<ScoredChunk> results) {
    }

    private final RetrievalService retrievalService;
    private final CurrentUser currentUser;

    public SearchController(RetrievalService retrievalService, CurrentUser currentUser) {
        this.retrievalService = retrievalService;
        this.currentUser = currentUser;
    }

    @Operation(summary = "Hybrid retrieval (keyword + vector, RRF-fused) returning ranked chunks with scores")
    @PostMapping
    public SearchResponse search(@Valid @RequestBody SearchRequestBody body) {
        List<ScoredChunk> results = retrievalService.search(
                currentUser.id(), body.query(), body.topK(), body.documentIds());
        return new SearchResponse(body.query(), results.size(), results);
    }
}
