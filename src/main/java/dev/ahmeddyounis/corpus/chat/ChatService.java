package dev.ahmeddyounis.corpus.chat;

import dev.ahmeddyounis.corpus.ops.CostEstimator;
import dev.ahmeddyounis.corpus.ops.ModelResilience;
import dev.ahmeddyounis.corpus.ops.RagMetrics;
import dev.ahmeddyounis.corpus.quota.QuotaService;
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
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
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
    private final ResponseCache responseCache;
    private final QuotaService quotas;
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
                       ResponseCache responseCache,
                       QuotaService quotas,
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
        this.responseCache = responseCache;
        this.quotas = quotas;
        this.heartbeatScheduler = heartbeatScheduler;
        this.provider = provider;
    }

    public SseEmitter stream(UUID userId, UUID conversationId, String message, Integer topK) {
        ConversationEntity conversation = conversationService.resolve(userId, conversationId, message);
        // Resolved before the cache is consulted so a keyless deployment still
        // returns its self-documenting 503 rather than serving cached answers
        // for a model it can no longer run.
        ChatClient client = client();
        // Before the emitter is built: once an SseEmitter is returned the status
        // line is already 200, and the client would have to parse an error event
        // to discover it was refused.
        quotas.checkAllowed(userId);
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

        // A request without a conversation id is by definition an opening turn.
        boolean firstTurn = conversationId == null;
        chatExecutor.submit(() -> {
            try {
                run(client, userId, conversation, message, topK, firstTurn, emitter, sendLock, finished);
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
        quotas.checkAllowed(userId);
        var cached = responseCache.lookup(userId, question, modelKey(), true);
        if (cached.isPresent()) {
            metrics.recordPhase("full_response", elapsedMs(start));
            ConversationEntity cachedConversation = conversationService.resolve(userId, null, question);
            chatMemory.add(cachedConversation.id().toString(), List.of(new UserMessage(question),
                    new AssistantMessage(cached.get().answer())));
            return new SyncAnswer(cached.get().answer(), cached.get().citations(), cachedConversation.id());
        }
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
            recordUsage(userId, syncUsage.getPromptTokens(), syncUsage.getCompletionTokens(),
                    metadata.getModel());
        }
        metrics.recordPhase("full_response", elapsedMs(start));
        List<Citation> syncCitations = citations(chunks);
        responseCache.put(userId, question, modelKey(), content, syncCitations);
        return new SyncAnswer(content, syncCitations, conversation.id());
    }

    private void run(ChatClient client, UUID userId, ConversationEntity conversation, String message,
                     Integer topK, boolean firstTurn, SseEmitter emitter, Object sendLock,
                     AtomicBoolean finished) {
        long start = System.nanoTime();
        try {
            var cached = responseCache.lookup(userId, message, modelKey(), firstTurn);
            if (cached.isPresent() && replayFromCache(userId, conversation, message, cached.get(),
                    emitter, sendLock, finished, start)) {
                return;
            }

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
            // Accumulated for the cache write; the tokens themselves are streamed
            // as they arrive, so this costs one buffer and no added latency.
            StringBuilder answer = new StringBuilder();
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
                        answer.append(delta);
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
            Double cost = usageSeen ? recordUsage(userId, promptTokens, completionTokens, model) : null;

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

            // After the client has its answer: a cache write must never delay a
            // response or fail a request that already succeeded.
            responseCache.put(userId, message, modelKey(), answer.toString(), citations(chunks));
        } catch (Exception e) {
            log.error("Chat stream failed for conversation {}: {}", conversation.id(), e.getMessage());
            send(emitter, sendLock, finished, SseEmitter.event().name("error").data(Map.of(
                    "message", e.getMessage() == null ? "Chat failed" : e.getMessage())));
            emitter.complete();
        }
    }

    /**
     * Replays a cached answer over the same SSE contract a generated one uses, so
     * a client cannot tell the difference beyond {@code usage.cached}.
     *
     * <p>The subtle part is the memory write. A cache hit never reaches the
     * {@link MessageChatMemoryAdvisor}, which is what normally persists the user
     * and assistant messages — so without writing them here the turn would vanish
     * from the conversation and the *next* turn would be answered without it.
     *
     * @return false if the memory write failed, in which case the caller falls
     *         through and generates normally rather than serving an answer the
     *         conversation would not remember.
     */
    private boolean replayFromCache(UUID userId, ConversationEntity conversation, String message,
                                    ResponseCache.Hit hit, SseEmitter emitter, Object sendLock,
                                    AtomicBoolean finished, long start) {
        String conversationId = conversation.id().toString();
        try {
            chatMemory.add(conversationId, List.of(new UserMessage(message),
                    new AssistantMessage(hit.answer())));
        } catch (Exception e) {
            log.warn("Could not record a cached turn in memory, generating instead: {}", e.toString());
            return false;
        }

        long totalMs = elapsedMs(start);
        metrics.recordPhase("first_token", totalMs);
        metrics.recordPhase("full_response", totalMs);

        send(emitter, sendLock, finished, SseEmitter.event().name("token")
                .data(Map.of("text", hit.answer())));
        send(emitter, sendLock, finished, SseEmitter.event().name("citations").data(hit.citations()));
        // Zero tokens and zero cost, because none were spent. Reporting the
        // original turn's usage would double-count spend that never happened.
        send(emitter, sendLock, finished, SseEmitter.event().name("usage").data(new UsageStats(
                0, 0, 0, 0.0, 0, totalMs, totalMs)));
        send(emitter, sendLock, finished, SseEmitter.event().name("done").data(Map.of(
                "conversationId", conversationId,
                "cached", true,
                "cacheSimilarity", hit.similarity())));
        emitter.complete();
        return true;
    }

    /** Namespaces cached answers, so a model swap cannot serve the old model's output. */
    private String modelKey() {
        return provider;
    }

    /**
     * Records token/cost metrics and accrues the caller's daily quota; returns the
     * estimated cost when the model is priced.
     *
     * <p>Both paths converge here after real spend, and neither a cache hit nor a
     * 503 reaches it — so a request that cost nothing never consumes budget.
     */
    private Double recordUsage(UUID userId, Integer promptTokens, Integer completionTokens, String model) {
        String modelId = CostEstimator.normalize(model);
        metrics.recordTokens(provider, modelId, promptTokens, completionTokens);
        Double cost = costEstimator.estimate(modelId, promptTokens, completionTokens)
                .map(estimated -> {
                    metrics.recordCost(provider, modelId, estimated);
                    return estimated;
                })
                .orElse(null);
        quotas.record(userId, promptTokens, completionTokens, cost);
        return cost;
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
