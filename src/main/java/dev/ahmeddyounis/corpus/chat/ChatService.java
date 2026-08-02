package dev.ahmeddyounis.corpus.chat;

import dev.ahmeddyounis.corpus.ops.CostEstimator;
import dev.ahmeddyounis.corpus.ops.ModelResilience;
import dev.ahmeddyounis.corpus.ops.RagMetrics;
import dev.ahmeddyounis.corpus.retrieval.RetrievalService;
import dev.ahmeddyounis.corpus.retrieval.ScoredChunk;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

/**
 * RAG chat: hybrid retrieval feeds numbered context into the prompt, the model
 * response streams back as SSE (token* → citations → usage → done), and
 * conversation memory persists via the JDBC chat-memory repository.
 *
 * <p>The ChatClient is assembled lazily from the auto-configured builder so that
 * keyless profiles (no chat model) start fine and fail per-request with a clear 503.
 */
@Service
public class ChatService {

    public record UsageStats(Integer promptTokens, Integer completionTokens, Integer totalTokens,
                             Double estimatedCostUsd, long retrievalMs, long firstTokenMs, long totalMs) {
    }

    public record SyncAnswer(String answer, List<Citation> citations, UUID conversationId) {
    }

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    private final ObjectProvider<ChatClient.Builder> chatClientBuilder;
    private final ChatMemory chatMemory;
    private final DocumentMetadataTools documentMetadataTools;
    private final RetrievalService retrievalService;
    private final ConversationService conversationService;
    private final RagPromptBuilder promptBuilder;
    private final AsyncTaskExecutor chatExecutor;
    private final RagMetrics metrics;
    private final CostEstimator costEstimator;
    private final ModelResilience resilience;
    private final CorpusChatProperties chatProperties;
    private final ScheduledExecutorService heartbeatScheduler;
    private final String provider;
    private volatile ChatClient chatClient;

    public ChatService(ObjectProvider<ChatClient.Builder> chatClientBuilder,
                       ChatMemory chatMemory,
                       DocumentMetadataTools documentMetadataTools,
                       RetrievalService retrievalService,
                       ConversationService conversationService,
                       RagPromptBuilder promptBuilder,
                       @Qualifier("chatExecutor") AsyncTaskExecutor chatExecutor,
                       RagMetrics metrics,
                       CostEstimator costEstimator,
                       ModelResilience resilience,
                       CorpusChatProperties chatProperties,
                       @Qualifier("sseHeartbeatScheduler") ScheduledExecutorService heartbeatScheduler,
                       @Value("${spring.ai.model.chat:none}") String provider) {
        this.chatClientBuilder = chatClientBuilder;
        this.chatMemory = chatMemory;
        this.documentMetadataTools = documentMetadataTools;
        this.retrievalService = retrievalService;
        this.conversationService = conversationService;
        this.promptBuilder = promptBuilder;
        this.chatExecutor = chatExecutor;
        this.metrics = metrics;
        this.costEstimator = costEstimator;
        this.resilience = resilience;
        this.chatProperties = chatProperties;
        this.heartbeatScheduler = heartbeatScheduler;
        this.provider = provider;
    }

    public SseEmitter stream(UUID userId, UUID conversationId, String message, Integer topK) {
        ConversationEntity conversation = conversationService.resolve(userId, conversationId, message);
        ChatClient client = client();
        SseEmitter emitter = new SseEmitter(chatProperties.sseTimeout().toMillis());
        // SseEmitter is not thread-safe and the heartbeat writes from a different
        // thread than the token loop, so every send goes through one lock.
        Object sendLock = new Object();
        AtomicBoolean finished = new AtomicBoolean();

        long interval = chatProperties.heartbeatInterval().toMillis();
        // Load balancers commonly idle out at 60s; comments keep the connection
        // alive through a long generation without disturbing the event contract.
        ScheduledFuture<?> heartbeat = heartbeatScheduler.scheduleAtFixedRate(
                () -> send(emitter, sendLock, finished, SseEmitter.event().comment("keep-alive")),
                interval, interval, TimeUnit.MILLISECONDS);
        Runnable stopHeartbeat = () -> {
            finished.set(true);
            heartbeat.cancel(false);
        };
        emitter.onCompletion(stopHeartbeat);
        emitter.onTimeout(stopHeartbeat);
        emitter.onError(error -> stopHeartbeat.run());

        chatExecutor.submit(() -> {
            try {
                run(client, userId, conversation, message, topK, emitter, sendLock, finished);
            } finally {
                stopHeartbeat.run();
            }
        });
        return emitter;
    }

    /** Serialized, best-effort send: a dead client must not break the other writer. */
    private static boolean send(SseEmitter emitter, Object sendLock, AtomicBoolean finished,
                                SseEmitter.SseEventBuilder event) {
        synchronized (sendLock) {
            if (finished.get()) {
                return false;
            }
            try {
                emitter.send(event);
                return true;
            } catch (Exception e) {
                finished.set(true);
                return false;
            }
        }
    }

    /** Non-streaming variant used by the MCP {@code ask_documents} tool. */
    public SyncAnswer answerSync(UUID userId, String question, Integer topK) {
        long start = System.nanoTime();
        List<ScoredChunk> chunks = retrievalService.search(userId, question, topK, null);
        metrics.recordPhase("retrieval", elapsedMs(start));
        metrics.recordRetrieval(chunks);
        ConversationEntity conversation = conversationService.resolve(userId, null, question);
        ChatResponse response = resilience.callChat(() -> client().prompt()
                .system(RagPromptBuilder.SYSTEM_PROMPT)
                .user(promptBuilder.userMessage(question, promptBuilder.contextBlock(chunks)))
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversation.id().toString()))
                .toolContext(Map.of(DocumentMetadataTools.USER_ID_CONTEXT_KEY, userId.toString()))
                .call()
                .chatResponse());
        String content = response != null && response.getResult() != null
                ? response.getResult().getOutput().getText()
                : "";
        ChatResponseMetadata metadata = response != null ? response.getMetadata() : null;
        Usage syncUsage = metadata != null ? metadata.getUsage() : null;
        if (syncUsage != null) {
            recordUsage(syncUsage.getPromptTokens(), syncUsage.getCompletionTokens(),
                    metadata.getModel());
        }
        metrics.recordPhase("full_response", elapsedMs(start));
        return new SyncAnswer(content, citations(chunks), conversation.id());
    }

    private void run(ChatClient client, UUID userId, ConversationEntity conversation, String message,
                     Integer topK, SseEmitter emitter, Object sendLock, AtomicBoolean finished) {
        long start = System.nanoTime();
        try {
            List<ScoredChunk> chunks = retrievalService.search(userId, message, topK, null);
            long retrievalMs = elapsedMs(start);
            metrics.recordPhase("retrieval", retrievalMs);
            metrics.recordRetrieval(chunks);

            Flux<ChatResponse> stream = resilience.streamChat(client.prompt()
                    .system(RagPromptBuilder.SYSTEM_PROMPT)
                    .user(promptBuilder.userMessage(message, promptBuilder.contextBlock(chunks)))
                    .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversation.id().toString()))
                    .toolContext(Map.of(DocumentMetadataTools.USER_ID_CONTEXT_KEY, userId.toString()))
                    .stream()
                    .chatResponse());

            long firstTokenMs = -1;
            int promptTokens = 0;
            int completionTokens = 0;
            boolean usageSeen = false;
            String model = null;
            // try-with-resources over toStream(): closing the stream cancels the
            // upstream subscription, so a client disconnect (send() throwing) stops
            // the provider call instead of letting it generate — and bill — to
            // completion in the background. toIterable() offers no such hook.
            try (Stream<ChatResponse> responses = stream.toStream()) {
                for (Iterator<ChatResponse> it = responses.iterator(); it.hasNext(); ) {
                    ChatResponse response = it.next();
                    String delta = response.getResult() != null && response.getResult().getOutput() != null
                            ? response.getResult().getOutput().getText()
                            : null;
                    if (delta != null && !delta.isEmpty()) {
                        if (firstTokenMs < 0) {
                            firstTokenMs = elapsedMs(start);
                            metrics.recordPhase("first_token", firstTokenMs);
                        }
                        if (!send(emitter, sendLock, finished,
                                SseEmitter.event().name("token").data(Map.of("text", delta)))) {
                            return; // client gone; try-with-resources cancels the model stream
                        }
                    }
                    if (response.getMetadata() != null) {
                        Usage candidate = response.getMetadata().getUsage();
                        if (candidate != null && candidate.getTotalTokens() != null
                                && candidate.getTotalTokens() > 0) {
                            // Streaming tool-calling turns concatenate one usage chunk per
                            // model round, and rounds do not accumulate on the streaming
                            // path — summing captures the tool round instead of dropping it.
                            promptTokens += candidate.getPromptTokens() != null ? candidate.getPromptTokens() : 0;
                            completionTokens +=
                                    candidate.getCompletionTokens() != null ? candidate.getCompletionTokens() : 0;
                            usageSeen = true;
                        }
                        String candidateModel = response.getMetadata().getModel();
                        if (candidateModel != null && !candidateModel.isBlank()) {
                            model = candidateModel;
                        }
                    }
                }
            }

            long totalMs = elapsedMs(start);
            metrics.recordPhase("full_response", totalMs);
            Double cost = usageSeen ? recordUsage(promptTokens, completionTokens, model) : null;

            send(emitter, sendLock, finished, SseEmitter.event().name("citations").data(citations(chunks)));
            send(emitter, sendLock, finished, SseEmitter.event().name("usage").data(new UsageStats(
                    usageSeen ? promptTokens : null,
                    usageSeen ? completionTokens : null,
                    usageSeen ? promptTokens + completionTokens : null,
                    cost,
                    retrievalMs, firstTokenMs, totalMs)));
            send(emitter, sendLock, finished, SseEmitter.event().name("done").data(Map.of(
                    "conversationId", conversation.id().toString())));
            emitter.complete();
        } catch (Exception e) {
            log.error("Chat stream failed for conversation {}: {}", conversation.id(), e.getMessage());
            send(emitter, sendLock, finished, SseEmitter.event().name("error").data(Map.of(
                    "message", e.getMessage() == null ? "Chat failed" : e.getMessage())));
            emitter.complete();
        }
    }

    /** Records token/cost metrics; returns the estimated cost when the model is priced. */
    private Double recordUsage(Integer promptTokens, Integer completionTokens, String model) {
        String modelId = CostEstimator.normalize(model);
        metrics.recordTokens(provider, modelId, promptTokens, completionTokens);
        return costEstimator.estimate(modelId, promptTokens, completionTokens)
                .map(cost -> {
                    metrics.recordCost(provider, modelId, cost);
                    return cost;
                })
                .orElse(null);
    }

    private List<Citation> citations(List<ScoredChunk> chunks) {
        List<Citation> citations = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            ScoredChunk chunk = chunks.get(i);
            citations.add(new Citation(i + 1, chunk.chunkId(), chunk.documentId(), chunk.filename(),
                    chunk.chunkIndex(), chunk.rrfScore()));
        }
        return citations;
    }

    private ChatClient client() {
        ChatClient local = chatClient;
        if (local != null) {
            return local;
        }
        synchronized (this) {
            if (chatClient == null) {
                ChatClient.Builder builder;
                try {
                    builder = chatClientBuilder.getIfAvailable();
                } catch (BeansException e) {
                    // The builder definition can exist while its ChatModel dependency
                    // doesn't (keyless profile): treat as "no chat model configured".
                    builder = null;
                }
                if (builder == null) {
                    throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                            "No chat model configured. Activate the 'local' profile (Ollama) or the "
                                    + "'cloud' profile with an API key; the keyless profile serves "
                                    + "ingestion and search only.");
                }
                chatClient = builder
                        .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
                        .defaultTools(documentMetadataTools)
                        .build();
            }
            return chatClient;
        }
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
