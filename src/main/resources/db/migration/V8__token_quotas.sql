-- Per-user daily token and cost accounting.
--
-- Micrometer already measures spend, but a metric is an export, not a store:
-- it is sampled, aggregated, and reset on restart, so nothing can be enforced
-- from it. These tables are the record of truth. See ADR 0013.

-- One row per user per UTC day. The primary key is what makes the accrual
-- atomic and replica-safe: a single INSERT ... ON CONFLICT DO UPDATE, with no
-- read-modify-write for two replicas to interleave.
CREATE TABLE token_usage (
    user_id           uuid   NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    usage_date        date   NOT NULL,
    prompt_tokens     bigint NOT NULL DEFAULT 0,
    completion_tokens bigint NOT NULL DEFAULT 0,
    cost_usd          numeric(12, 6) NOT NULL DEFAULT 0,
    requests          bigint NOT NULL DEFAULT 0,
    PRIMARY KEY (user_id, usage_date)
);

-- Optional per-user overrides. A NULL column means "use the configured default",
-- so raising one user's ceiling never means restating the rest.
CREATE TABLE user_quotas (
    user_id         uuid PRIMARY KEY REFERENCES users (id) ON DELETE CASCADE,
    daily_tokens    bigint,
    daily_cost_usd  numeric(12, 6),
    updated_at      timestamptz NOT NULL DEFAULT now()
);
