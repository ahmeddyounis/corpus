package dev.ahmeddyounis.corpus.quota;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.LocalDate;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Per-user daily token and cost ceilings.
 *
 * <p>Corpus already measured spend through Micrometer, but a metric is an
 * export, not a store: sampled, aggregated, and reset on restart. Enforcement
 * needs a durable per-user record, which is what {@code token_usage} is.
 *
 * <p>The window is a UTC calendar day, not a rolling window. A rolling window
 * needs per-request history to expire; a calendar day is one primary key and
 * one upsert, and "you get a fresh budget at midnight UTC" is something a user
 * can reason about without reading the implementation.
 */
@Service
public class QuotaService {

    private static final Logger log = LoggerFactory.getLogger(QuotaService.class);

    /** Distinct from a rate-limit 429 so a client can tell "slow down" from "you are out". */
    public static final String QUOTA_ERROR_CODE = "token_quota_exhausted";

    public record Usage(LocalDate date, long promptTokens, long completionTokens, double costUsd,
                        long requests, long tokenLimit, double costLimit) {

        public long totalTokens() {
            return promptTokens + completionTokens;
        }

        public long remainingTokens() {
            return Math.max(0, tokenLimit - totalTokens());
        }

        public double remainingCostUsd() {
            return Math.max(0, costLimit - costUsd);
        }

        public boolean exhausted() {
            return totalTokens() >= tokenLimit || costUsd >= costLimit;
        }
    }

    private final QuotaDao dao;
    private final CorpusQuotaProperties properties;
    private final MeterRegistry registry;
    private final Clock clock;

    public QuotaService(QuotaDao dao, CorpusQuotaProperties properties, MeterRegistry registry, Clock clock) {
        this.dao = dao;
        this.properties = properties;
        this.registry = registry;
        this.clock = clock;
    }

    public Usage usage(UUID userId) {
        QuotaDao.Limits limits = dao.limits(userId, properties.dailyTokens(), properties.dailyCostUsd());
        return dao.usage(userId, today(), limits.tokens(), limits.costUsd());
    }

    /**
     * Rejects the request if the user has already spent their day's budget.
     *
     * <p>Checked before the work starts and accrued after it finishes, so a user
     * can overshoot by at most one response. Serialising the two would mean
     * holding a lock across an LLM call — trading a bounded, documented overshoot
     * for an unbounded one under contention.
     *
     * @throws ResponseStatusException 429 with {@link #QUOTA_ERROR_CODE}. Not 402:
     *         RFC 9110 reserves that status and it is not a payment failure.
     */
    public void checkAllowed(UUID userId) {
        if (!properties.enabled()) {
            return;
        }
        Usage usage = usage(userId);
        if (usage.exhausted()) {
            Counter.builder("corpus.quota.blocked")
                    .description("Requests refused because the caller's daily budget was spent")
                    .register(registry)
                    .increment();
            log.info("Quota exhausted for user {}: {} tokens, ${} spent today",
                    userId, usage.totalTokens(), usage.costUsd());
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "%s: daily budget spent (%d/%d tokens, $%.4f/$%.4f). Resets at 00:00 UTC."
                            .formatted(QUOTA_ERROR_CODE, usage.totalTokens(), usage.tokenLimit(),
                                    usage.costUsd(), usage.costLimit()));
        }
    }

    /**
     * Records what a completed turn actually spent. Never called for a cache hit
     * or a 503 — a request that cost nothing must not consume budget.
     */
    public void record(UUID userId, Integer promptTokens, Integer completionTokens, Double costUsd) {
        if (!properties.enabled()) {
            return;
        }
        try {
            dao.accrue(userId, today(),
                    promptTokens != null ? promptTokens : 0,
                    completionTokens != null ? completionTokens : 0,
                    costUsd != null ? costUsd : 0.0);
        } catch (Exception e) {
            // Losing an accrual understates spend; failing the request the user
            // already paid for is worse.
            log.warn("Could not record quota usage for user {}: {}", userId, e.toString());
        }
    }

    private LocalDate today() {
        return LocalDate.now(clock);
    }
}
