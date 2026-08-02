-- Distributed rate-limit buckets (Bucket4j's PostgreSQL proxy manager).
-- Keeping these in the existing database rather than adding Redis preserves the
-- single-datastore decision in ADR 0002; the column names match Bucket4j's
-- defaults except for the table itself.
CREATE TABLE rate_limit_bucket (
    id    varchar(200) PRIMARY KEY,
    state bytea
);
