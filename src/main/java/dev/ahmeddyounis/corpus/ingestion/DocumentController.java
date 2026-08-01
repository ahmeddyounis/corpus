package dev.ahmeddyounis.corpus.ingestion;

import dev.ahmeddyounis.corpus.api.PageResponse;
import dev.ahmeddyounis.corpus.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/documents")
public class DocumentController {

    public record DocumentResponse(UUID id, String filename, String contentType, long sizeBytes,
                                   DocumentStatus status, String error, int chunkCount, Instant createdAt) {

        static DocumentResponse from(DocumentEntity doc) {
            return new DocumentResponse(doc.id(), doc.filename(), doc.contentType(), doc.sizeBytes(),
                    doc.status(), doc.error(), doc.chunkCount(), doc.createdAt());
        }
    }

    private final IngestionService ingestionService;
    private final CurrentUser currentUser;

    public DocumentController(IngestionService ingestionService, CurrentUser currentUser) {
        this.ingestionService = ingestionService;
        this.currentUser = currentUser;
    }

    @Operation(summary = "Upload a document (PDF, Markdown, DOCX, TXT); ingestion runs asynchronously")
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.ACCEPTED)
    public DocumentResponse upload(@RequestParam("file") MultipartFile file) throws IOException {
        if (file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "File is empty");
        }
        String filename = file.getOriginalFilename();
        if (filename != null && filename.length() > 255) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Filename exceeds 255 characters");
        }
        if (!IngestionService.supported(filename)) {
            throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                    "Supported extensions: " + IngestionService.SUPPORTED_EXTENSIONS);
        }
        try {
            return DocumentResponse.from(
                    ingestionService.upload(currentUser.id(), filename, file.getContentType(), file.getBytes()));
        } catch (DataIntegrityViolationException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A document named '" + filename + "' already exists; delete it first.");
        } catch (IngestionCapacityException e) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
        }
    }

    @Operation(summary = "List the caller's documents with ingestion status (paginated, newest first)")
    @GetMapping
    public PageResponse<DocumentResponse> list(
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC)
            Pageable pageable) {
        return PageResponse.of(ingestionService.list(currentUser.id(), pageable), DocumentResponse::from);
    }

    @Operation(summary = "Delete a document and its chunks/embeddings")
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        if (!ingestionService.delete(currentUser.id(), id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Document not found");
        }
    }
}
