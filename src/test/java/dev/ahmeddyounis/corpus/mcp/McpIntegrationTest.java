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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Raw streamable-HTTP JSON-RPC contract test: initialize handshake, session reuse,
 * tools/list, and tools/call for all three Corpus tools. The test profile maps
 * anonymous MCP calls to the demo user; a bearer test proves per-user scoping.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class McpIntegrationTest extends AbstractIntegrationTest {

    private static final String FIXTURE = """
            # Key management runbook
            The quarterly signing-secret rotation is tracked under change ticket KMR-4411.
            Rotations happen in the key management console and must be verified by two
            operators. Emergency rotation follows the same steps compressed to one hour.
            """;

    private static final String BEARER_FIXTURE = """
            # Zebra migration checklist
            The zebra data migration ZBR-777 moves archival shards to cold storage.
            Verify checksums before deleting source shards.
            """;

    private HttpClient http;
    private String token;
    private String mcpUserToken;
    private String sessionId;

    @Autowired
    private UserRepository users;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private JwtService jwtService;

    @BeforeAll
    void setUp() throws Exception {
        http = HttpClient.newHttpClient();
        token = demoToken();
        if (listDocuments(token).stream().noneMatch(d -> "kmr.md".equals(d.get("filename"))
                && "READY".equals(d.get("status")))) {
            uploadAndAwaitReady(token, "kmr.md", FIXTURE.getBytes(StandardCharsets.UTF_8));
        }
        sessionId = initializeSession(null);
    }

    @AfterAll
    void tearDown() {
        http.close();
    }

    private HttpResponse<String> rpc(String json, String session, String bearer) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/mcp"))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(json));
        if (session != null) {
            builder.header("Mcp-Session-Id", session);
        }
        if (bearer != null) {
            builder.header("Authorization", "Bearer " + bearer);
        }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    /** Streamable HTTP responses may be plain JSON or SSE-framed; normalize to JSON. */
    private static String jsonOf(HttpResponse<String> response) {
        String body = response.body();
        if (body != null && body.lines().anyMatch(l -> l.startsWith("data:"))) {
            return body.lines().filter(l -> l.startsWith("data:"))
                    .map(l -> l.substring("data:".length()).strip())
                    .collect(Collectors.joining());
        }
        return body == null ? "" : body;
    }

    private String initializeSession(String bearer) throws Exception {
        HttpResponse<String> init = rpc("""
                {"jsonrpc":"2.0","id":1,"method":"initialize","params":{
                  "protocolVersion":"2025-06-18",
                  "capabilities":{},
                  "clientInfo":{"name":"corpus-it","version":"0.0.1"}}}
                """, null, bearer);
        assertThat(init.statusCode()).as(init.body()).isEqualTo(200);
        String json = jsonOf(init);
        assertThat(json).contains("\"serverInfo\"").contains("corpus");
        String session = init.headers().firstValue("mcp-session-id").orElse(null);

        HttpResponse<String> initialized = rpc(
                "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}", session, bearer);
        assertThat(initialized.statusCode()).isIn(200, 202);
        return session;
    }

    @Test
    void toolsAreListed() throws Exception {
        HttpResponse<String> list = rpc(
                "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}", sessionId, null);

        assertThat(list.statusCode()).as(list.body()).isEqualTo(200);
        String json = jsonOf(list);
        assertThat(json)
                .contains("search_documents")
                .contains("ask_documents")
                .contains("list_documents");
    }

    @Test
    void searchDocumentsToolReturnsScopedHits() throws Exception {
        HttpResponse<String> call = rpc("""
                {"jsonrpc":"2.0","id":3,"method":"tools/call","params":{
                  "name":"search_documents","arguments":{"query":"signing-secret rotation ticket"}}}
                """, sessionId, null);

        assertThat(call.statusCode()).as(call.body()).isEqualTo(200);
        String json = jsonOf(call);
        assertThat(json).contains("kmr.md").contains("KMR-4411");
        assertThat(json).doesNotContain("\"isError\":true");
    }

    @Test
    void askDocumentsToolAnswersWithCitations() throws Exception {
        HttpResponse<String> call = rpc("""
                {"jsonrpc":"2.0","id":4,"method":"tools/call","params":{
                  "name":"ask_documents","arguments":{"question":"How does signing secret rotation work?"}}}
                """, sessionId, null);

        assertThat(call.statusCode()).as(call.body()).isEqualTo(200);
        String json = jsonOf(call);
        assertThat(json).contains("STUB_ANSWER_COMPLETE").contains("citations");
    }

    @Test
    void listDocumentsToolShowsTheCorpus() throws Exception {
        HttpResponse<String> call = rpc("""
                {"jsonrpc":"2.0","id":5,"method":"tools/call","params":{
                  "name":"list_documents","arguments":{}}}
                """, sessionId, null);

        assertThat(call.statusCode()).as(call.body()).isEqualTo(200);
        assertThat(jsonOf(call)).contains("kmr.md").contains("READY");
    }

    @Test
    void bearerTokenScopesToolCallsToThatUser() throws Exception {
        UserAccount mcpUser = users.findByUsername("mcp-user")
                .orElseGet(() -> users.save(UserAccount.create(
                        "mcp-user", passwordEncoder.encode("x"))));
        mcpUserToken = jwtService.issue(mcpUser.id(), mcpUser.username());
        if (listDocuments(mcpUserToken).stream().noneMatch(d -> "zebra.md".equals(d.get("filename"))
                && "READY".equals(d.get("status")))) {
            uploadAndAwaitReady(mcpUserToken, "zebra.md", BEARER_FIXTURE.getBytes(StandardCharsets.UTF_8));
        }
        String bearerSession = initializeSession(mcpUserToken);

        HttpResponse<String> asBearer = rpc("""
                {"jsonrpc":"2.0","id":6,"method":"tools/call","params":{
                  "name":"search_documents","arguments":{"query":"ZBR-777"}}}
                """, bearerSession, mcpUserToken);
        assertThat(asBearer.statusCode()).isEqualTo(200);
        assertThat(jsonOf(asBearer)).contains("zebra.md");

        HttpResponse<String> asAnonymous = rpc("""
                {"jsonrpc":"2.0","id":7,"method":"tools/call","params":{
                  "name":"search_documents","arguments":{"query":"ZBR-777"}}}
                """, sessionId, null);
        assertThat(asAnonymous.statusCode()).isEqualTo(200);
        assertThat(jsonOf(asAnonymous)).doesNotContain("zebra.md");
    }

}
