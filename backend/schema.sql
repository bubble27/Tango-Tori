CREATE TABLE IF NOT EXISTS compound_cache (
    compound    TEXT    PRIMARY KEY,
    meaning     TEXT    NOT NULL,
    created_at  INTEGER NOT NULL
);

-- Per-device daily token-cost accounting. We meter the real Anthropic token
-- cost of each LLM call (in micro-USD = millionths of a dollar) and cap it at a
-- daily budget, instead of counting requests. spent_micro_usd accumulates only
-- on actual LLM calls; cache hits are free and never touch this table.
-- Replaces the old request-count `rate_limits` table (now unused; safe to drop).
CREATE TABLE IF NOT EXISTS daily_token_cost (
    device_id        TEXT    NOT NULL,
    date             TEXT    NOT NULL,
    spent_micro_usd  INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (device_id, date)
);

-- Caches in-context word-sense disambiguation results, keyed on
-- language + word + sentence. result is JSON: {"id": <number>, "meaning": "..."}.
-- Contains user-typed sentences → rows are auto-deleted after 7 days by the
-- worker's nightly cron (data-retention policy declared on Play).
CREATE TABLE IF NOT EXISTS disambiguation_cache (
    cache_key   TEXT    PRIMARY KEY,
    result      TEXT    NOT NULL,
    created_at  INTEGER NOT NULL
);

-- Caches the in-expression meaning of each component word of an idiom/compound,
-- keyed on the expression alone (sentence-independent: ~生 = "student of ~" in
-- 高校生 regardless of sentence). result is a JSON array of glosses parallel to
-- the components. Vocabulary, not user data → not swept by the retention cron.
CREATE TABLE IF NOT EXISTS expression_parts_cache (
    cache_key   TEXT    PRIMARY KEY,
    result      TEXT    NOT NULL,
    created_at  INTEGER NOT NULL
);

-- Server-verified usage tier per device (1 = free, 8 / 20 = purchased boost).
-- Written by /verify_purchase after checking the Play purchase token against
-- the Google Play Developer API; read on every metered request. The app sends
-- its full owned-purchase list each time, so the tier is set absolutely (a
-- revoked/refunded purchase downgrades on the next verification).
CREATE TABLE IF NOT EXISTS device_tier (
    device_id    TEXT    PRIMARY KEY,
    tier         INTEGER NOT NULL,
    product_ids  TEXT,
    verified_at  INTEGER NOT NULL
);
