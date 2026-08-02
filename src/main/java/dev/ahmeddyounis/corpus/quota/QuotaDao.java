package dev.ahmeddyounis.corpus.quota;

import java.time.LocalDate;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class QuotaDao {

    public record Limits(long tokens, double costUsd) {
    }

    private final JdbcClient jdbc;

    public QuotaDao(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Per-user overrides where set, configured defaults otherwise. */
    public Limits limits(UUID userId, long defaultTokens, double defaultCost) {
        return jdbc.sql("""
                        SELECT coalesce(daily_tokens, :defaultTokens)   AS tokens,
                               coalesce(daily_cost_usd, :defaultCost)   AS cost_usd
                          FROM user_quotas WHERE user_id = :userId
                        """)
                .param("userId", userId)
                .param("defaultTokens", defaultTokens)
                .param("defaultCost", defaultCost)
                .query((rs, rowNum) -> new Limits(rs.getLong("tokens"), rs.getDouble("cost_usd")))
                .optional()
                .orElse(new Limits(defaultTokens, defaultCost));
    }

    public QuotaService.Usage usage(UUID userId, LocalDate date, long tokenLimit, double costLimit) {
        return jdbc.sql("""
                        SELECT prompt_tokens, completion_tokens, cost_usd, requests
                          FROM token_usage WHERE user_id = :userId AND usage_date = :date
                        """)
                .param("userId", userId)
                .param("date", date)
                .query((rs, rowNum) -> new QuotaService.Usage(date,
                        rs.getLong("prompt_tokens"), rs.getLong("completion_tokens"),
                        rs.getDouble("cost_usd"), rs.getLong("requests"), tokenLimit, costLimit))
                .optional()
                .orElse(new QuotaService.Usage(date, 0, 0, 0, 0, tokenLimit, costLimit));
    }

    /**
     * One statement, so concurrent turns on different replicas cannot lose an
     * accrual to an interleaved read-modify-write.
     */
    public void accrue(UUID userId, LocalDate date, long promptTokens, long completionTokens, double costUsd) {
        jdbc.sql("""
                        INSERT INTO token_usage
                            (user_id, usage_date, prompt_tokens, completion_tokens, cost_usd, requests)
                        VALUES (:userId, :date, :prompt, :completion, :cost, 1)
                        ON CONFLICT (user_id, usage_date) DO UPDATE SET
                            prompt_tokens     = token_usage.prompt_tokens + :prompt,
                            completion_tokens = token_usage.completion_tokens + :completion,
                            cost_usd          = token_usage.cost_usd + :cost,
                            requests          = token_usage.requests + 1
                        """)
                .param("userId", userId)
                .param("date", date)
                .param("prompt", promptTokens)
                .param("completion", completionTokens)
                .param("cost", costUsd)
                .update();
    }

    /** Sets or clears one user's override; nulls fall back to the configured default. */
    public void setOverride(UUID userId, Long dailyTokens, Double dailyCostUsd) {
        jdbc.sql("""
                        INSERT INTO user_quotas (user_id, daily_tokens, daily_cost_usd)
                        VALUES (:userId, :tokens, :cost)
                        ON CONFLICT (user_id) DO UPDATE SET
                            daily_tokens = :tokens, daily_cost_usd = :cost, updated_at = now()
                        """)
                .param("userId", userId)
                .param("tokens", dailyTokens)
                .param("cost", dailyCostUsd)
                .update();
    }
}
