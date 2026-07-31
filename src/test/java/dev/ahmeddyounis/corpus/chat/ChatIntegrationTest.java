package dev.ahmeddyounis.corpus.chat;

import dev.ahmeddyounis.corpus.security.JwtService;
import dev.ahmeddyounis.corpus.security.UserAccount;
import dev.ahmeddyounis.corpus.security.UserRepository;
import dev.ahmeddyounis.corpus.support.AbstractIntegrationTest;
import dev.ahmeddyounis.corpus.support.StubChatModel;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ChatIntegrationTest extends AbstractIntegrationTest {

    private static final String FIXTURE = """
            # Master services agreement
            The termination clause allows either party to end the agreement with thirty days
            written notice after a material breach that remains uncured through the notice
            period. Data export is provided in a machine-readable format within ninety days.
            """;

    record SseEvent(String name, String data) {
    }

    @Autowired
    private UserRepository users;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private JwtService jwtService;

    private String token;
    private String intruderToken;

    @BeforeAll
    void seed() {
        token = demoToken();
        boolean present = listDocuments(token).stream()
                .anyMatch(d -> "msa.md".equals(d.get("filename")) && "READY".equals(d.get("status")));
        if (!present) {
            uploadAndAwaitReady(token, "msa.md", FIXTURE.getBytes(StandardCharsets.UTF_8));
        }
        UserAccount intruder = users.findByUsername("chat-intruder")
                .orElseGet(() -> users.save(UserAccount.create("chat-intruder", passwordEncoder.encode("x"))));
        intruderToken = jwtService.issue(intruder.id(), intruder.username());
    }

    private HttpResponse<String> rawChat(String authToken, String jsonBody) throws Exception {
        try (HttpClient http = HttpClient.newHttpClient()) {
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/chat"))
                    .header("Authorization", "Bearer " + authToken)
                    .header("Content-Type", "application/json")
                    .header("Accept", "text/event-stream")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .build();
            return http.send(request, HttpResponse.BodyHandlers.ofString());
        }
    }

    private List<SseEvent> chat(String message, String conversationId) throws Exception {
        String body = conversationId == null
                ? "{\"message\":\"" + message + "\"}"
                : "{\"message\":\"" + message + "\",\"conversationId\":\"" + conversationId + "\"}";
        HttpResponse<String> response = rawChat(token, body);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type").orElse("")).contains("text/event-stream");
        return parseSse(response.body());
    }

    private static List<SseEvent> parseSse(String body) {
        List<SseEvent> events = new ArrayList<>();
        for (String block : body.split("\n\n")) {
            String name = null;
            StringBuilder data = new StringBuilder();
            for (String line : block.split("\n")) {
                if (line.startsWith("event:")) {
                    name = line.substring("event:".length()).strip();
                } else if (line.startsWith("data:")) {
                    data.append(line.substring("data:".length()).strip());
                }
            }
            if (name != null) {
                events.add(new SseEvent(name, data.toString()));
            }
        }
        return events;
    }

    private static String conversationIdOf(List<SseEvent> events) {
        SseEvent done = events.stream().filter(e -> e.name().equals("done")).findFirst().orElseThrow();
        Matcher m = Pattern.compile("\"conversationId\"\\s*:\\s*\"([0-9a-f-]{36})\"").matcher(done.data());
        assertThat(m.find()).as("done event carries conversationId: %s", done.data()).isTrue();
        return m.group(1);
    }

    @Test
    void streamDeliversTokensThenCitationsUsageAndDone() throws Exception {
        List<SseEvent> events = chat("What does the termination clause say?", null);

        List<String> names = events.stream().map(SseEvent::name).toList();
        assertThat(names).startsWith("token");
        assertThat(names).containsSubsequence("token", "citations", "usage", "done");
        assertThat(names).doesNotContain("error");

        String answer = events.stream().filter(e -> e.name().equals("token"))
                .map(SseEvent::data).reduce("", String::concat);
        assertThat(answer).contains("STUB_ANSWER_COMPLETE");

        SseEvent citations = events.stream().filter(e -> e.name().equals("citations")).findFirst().orElseThrow();
        assertThat(citations.data()).contains("msa.md");

        SseEvent usage = events.stream().filter(e -> e.name().equals("usage")).findFirst().orElseThrow();
        assertThat(usage.data())
                .contains("\"promptTokens\":" + StubChatModel.PROMPT_TOKENS)
                .contains("\"completionTokens\":" + StubChatModel.COMPLETION_TOKENS);
    }

    @Test
    void conversationMemoryPersistsAcrossTurns() throws Exception {
        List<SseEvent> first = chat("Summarize the termination clause please", null);
        String conversationId = conversationIdOf(first);

        List<SseEvent> second = chat("And what about data export?", conversationId);
        assertThat(conversationIdOf(second)).isEqualTo(conversationId);

        ResponseEntity<Map<String, Object>> history = restClient().get()
                .uri("/api/conversations/" + conversationId)
                .header("Authorization", "Bearer " + token)
                .retrieve()
                .toEntity(new ParameterizedTypeReference<>() {
                });
        assertThat(history.getStatusCode().value()).isEqualTo(200);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> messages = (List<Map<String, Object>>) history.getBody().get("messages");
        assertThat(messages.size()).isGreaterThanOrEqualTo(4);
        String rendered = messages.toString();
        assertThat(rendered).contains("termination clause").contains("data export");
    }

    @Test
    void foreignConversationIsRejected() throws Exception {
        List<SseEvent> mine = chat("A question to create a conversation", null);
        String conversationId = conversationIdOf(mine);

        ResponseEntity<String> historyDenied = restClient().get()
                .uri("/api/conversations/" + conversationId)
                .header("Authorization", "Bearer " + intruderToken)
                .retrieve()
                .toEntity(String.class);
        assertThat(historyDenied.getStatusCode().value()).isEqualTo(404);

        HttpResponse<String> chatDenied = rawChat(intruderToken,
                "{\"message\":\"hi\",\"conversationId\":\"" + conversationId + "\"}");
        assertThat(chatDenied.statusCode()).isEqualTo(404);
    }
}
