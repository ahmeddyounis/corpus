package dev.ahmeddyounis.corpus.api;

import dev.ahmeddyounis.corpus.security.JwtService;
import dev.ahmeddyounis.corpus.security.UserAccount;
import dev.ahmeddyounis.corpus.security.UserRepository;
import dev.ahmeddyounis.corpus.support.AbstractIntegrationTest;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PaginationIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private UserRepository users;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private JwtService jwtService;

    private String token;
    private String otherToken;

    @BeforeAll
    void seed() {
        UserAccount owner = users.findByUsername("pagination-user")
                .orElseGet(() -> users.save(UserAccount.create("pagination-user", passwordEncoder.encode("x"))));
        token = jwtService.issue(owner.id(), owner.username());
        UserAccount other = users.findByUsername("pagination-other")
                .orElseGet(() -> users.save(UserAccount.create("pagination-other", passwordEncoder.encode("x"))));
        otherToken = jwtService.issue(other.id(), other.username());

        if (listDocuments(token).size() < 25) {
            for (int i = 0; i < 25; i++) {
                String filename = "page-doc-%02d.md".formatted(i);
                uploadDocument(token, filename,
                        ("# Doc " + i + "\nPagination fixture body.").getBytes(StandardCharsets.UTF_8));
            }
        }
    }

    private Map<String, Object> page(String authToken, String uri) {
        return restClient().get().uri(uri)
                .header("Authorization", "Bearer " + authToken)
                .retrieve()
                .body(new ParameterizedTypeReference<>() {
                });
    }

    @Test
    void documentListingIsPagedWithTotalsAndHasNext() {
        Map<String, Object> first = page(token, "/api/documents?page=0&size=20");

        assertThat(first).containsKeys("items", "page", "size", "total", "hasNext");
        assertThat((List<?>) first.get("items")).hasSize(20);
        assertThat(((Number) first.get("total")).longValue()).isGreaterThanOrEqualTo(25);
        assertThat(first.get("hasNext")).isEqualTo(true);

        Map<String, Object> second = page(token, "/api/documents?page=1&size=20");
        assertThat((List<?>) second.get("items")).isNotEmpty();
        assertThat(second.get("page")).isEqualTo(1);
    }

    @Test
    void pageSizeIsCappedSoACallerCannotRequestTheWholeTable() {
        Map<String, Object> capped = page(token, "/api/documents?page=0&size=5000");

        assertThat(((Number) capped.get("size")).intValue()).isLessThanOrEqualTo(100);
    }

    @Test
    void conversationIndexListsOnlyTheCallersConversations() {
        for (int i = 0; i < 2; i++) {
            restClient().post().uri("/api/chat")
                    .header("Authorization", "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.TEXT_EVENT_STREAM)
                    .body(Map.of("message", "Pagination conversation probe " + i))
                    .retrieve()
                    .toBodilessEntity();
        }

        Map<String, Object> mine = page(token, "/api/conversations");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) mine.get("items");
        assertThat(items).isNotEmpty();
        assertThat(items.getFirst()).containsKeys("id", "title", "createdAt");

        Map<String, Object> theirs = page(otherToken, "/api/conversations");
        assertThat(((Number) theirs.get("total")).longValue())
                .as("another user must not see these conversations")
                .isZero();
    }
}
