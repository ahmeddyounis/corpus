package dev.ahmeddyounis.corpus.mcp;

import dev.ahmeddyounis.corpus.security.JwtService;
import dev.ahmeddyounis.corpus.security.UserAccount;
import dev.ahmeddyounis.corpus.security.UserRepository;
import dev.ahmeddyounis.corpus.support.AbstractIntegrationTest;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The stateless MCP transport keeps no per-JVM session map, so any replica can
 * answer any call and no sticky sessions are needed. Every request here omits
 * {@code Mcp-Session-Id} entirely — under the streamable transport that would fail
 * with "Session not found".
 */
@TestPropertySource(properties = "spring.ai.mcp.server.protocol=STATELESS")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class McpStatelessIntegrationTest extends AbstractIntegrationTest {

    private static final String FIXTURE = """
            # Stateless transport notes
            The migration identifier STL-2026 tracks the move to the stateless MCP
            transport, which removes the per-instance session map.
            """;

    private static final String OTHER_FIXTURE = """
            # Ledger reconciliation
            Reconciliation job LDG-8080 compares nightly balances across shards.
            """;

    @Autowired
    private UserRepository users;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private JwtService jwtService;

    private HttpClient http;
    private String demoToken;
    private String otherToken;

    @BeforeAll
    void setUp() {
        http = HttpClient.newHttpClient();
        demoToken = demoToken();
        if (listDocuments(demoToken).stream().noneMatch(d -> "stateless.md".equals(d.get("filename"))
                && "READY".equals(d.get("status")))) {
            uploadAndAwaitReady(demoToken, "stateless.md", FIXTURE.getBytes(StandardCharsets.UTF_8));
        }
        seedOtherAccount();
    }

    private void seedOtherAccount() {
        UserAccount other = users.findByUsername("stateless-other")
                .orElseGet(() -> users.save(UserAccount.create("stateless-other", passwordEncoder.encode("x"))));
        otherToken = jwtService.issue(other.id(), other.username());
        if (listDocuments(otherToken).stream().noneMatch(d -> "ledger.md".equals(d.get("filename"))
                && "READY".equals(d.get("status")))) {
            uploadAndAwaitReady(otherToken, "ledger.md", OTHER_FIXTURE.getBytes(StandardCharsets.UTF_8));
        }
    }

    @AfterAll
    void tearDown() {
        http.close();
    }

    /** Deliberately sends no Mcp-Session-Id and performs no initialize handshake. */
    private HttpResponse<String> rpc(String json, String bearer) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/mcp"))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(json));
        if (bearer != null) {
            builder.header("Authorization", "Bearer " + bearer);
        }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String jsonOf(HttpResponse<String> response) {
        String body = response.body();
        if (body != null && body.lines().anyMatch(l -> l.startsWith("data:"))) {
            return body.lines().filter(l -> l.startsWith("data:"))
                    .map(l -> l.substring("data:".length()).strip())
                    .collect(Collectors.joining());
        }
        return body == null ? "" : body;
    }

    @Test
    void toolsAreListedWithoutASession() throws Exception {
        HttpResponse<String> list = rpc("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}", null);

        assertThat(list.statusCode()).as(list.body()).isEqualTo(200);
        assertThat(jsonOf(list))
                .contains("search_documents")
                .contains("ask_documents")
                .contains("list_documents");
    }

    @Test
    void toolCallsSucceedWithoutASession() throws Exception {
        HttpResponse<String> call = rpc("""
                {"jsonrpc":"2.0","id":2,"method":"tools/call","params":{
                  "name":"search_documents","arguments":{"query":"STL-2026 stateless transport"}}}
                """, null);

        assertThat(call.statusCode()).as(call.body()).isEqualTo(200);
        assertThat(jsonOf(call)).contains("stateless.md").doesNotContain("\"isError\":true");
    }

    /**
     * The security context must still be readable on the stateless transport's
     * request thread, or MCP would silently act as the wrong user.
     */
    @Test
    void bearerTokenStillScopesToolCalls() throws Exception {
        HttpResponse<String> asOther = rpc("""
                {"jsonrpc":"2.0","id":3,"method":"tools/call","params":{
                  "name":"search_documents","arguments":{"query":"LDG-8080 reconciliation"}}}
                """, otherToken);
        assertThat(asOther.statusCode()).isEqualTo(200);
        assertThat(jsonOf(asOther)).contains("ledger.md");

        HttpResponse<String> asAnonymousDemo = rpc("""
                {"jsonrpc":"2.0","id":4,"method":"tools/call","params":{
                  "name":"search_documents","arguments":{"query":"LDG-8080 reconciliation"}}}
                """, null);
        assertThat(asAnonymousDemo.statusCode()).isEqualTo(200);
        assertThat(jsonOf(asAnonymousDemo))
                .as("the anonymous demo identity must not see another user's documents")
                .doesNotContain("ledger.md");
    }
}
