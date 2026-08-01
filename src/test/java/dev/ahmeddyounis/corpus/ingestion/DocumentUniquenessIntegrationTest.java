package dev.ahmeddyounis.corpus.ingestion;

import dev.ahmeddyounis.corpus.chat.DocumentMetadataTools;
import dev.ahmeddyounis.corpus.support.AbstractIntegrationTest;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentUniquenessIntegrationTest extends AbstractIntegrationTest {

    private static final byte[] CONTENT =
            "# Unique\nA document used to prove filenames are unique per user.".getBytes(StandardCharsets.UTF_8);

    @Autowired
    private DocumentMetadataTools tools;

    @Test
    void uploadingTheSameFilenameTwiceIsRejected() {
        String token = demoToken();
        String filename = "uniqueness-probe.md";

        ResponseEntity<Map<String, Object>> first = uploadDocument(token, filename, CONTENT);
        assertThat(first.getStatusCode().value()).isEqualTo(202);

        ResponseEntity<Map<String, Object>> second = uploadDocument(token, filename, CONTENT);
        assertThat(second.getStatusCode().value()).isEqualTo(409);
    }

    /**
     * Duplicate rows previously made this tool throw permanently for that filename.
     * With the unique index the lookup stays single-valued by construction.
     */
    @Test
    void documentMetadataLookupStaysSingleValued() {
        String token = demoToken();
        String filename = "metadata-lookup-probe.md";
        uploadAndAwaitReady(token, filename, CONTENT);
        uploadDocument(token, filename, CONTENT);

        String userId = (String) restClient().get().uri("/api/auth/me")
                .header("Authorization", "Bearer " + token)
                .retrieve()
                .body(new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {
                })
                .get("id");

        String info = tools.documentInfo(filename,
                new ToolContext(Map.of(DocumentMetadataTools.USER_ID_CONTEXT_KEY, userId)));

        assertThat(info).contains(filename).contains("status=");
    }
}
