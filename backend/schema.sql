CREATE TABLE IF NOT EXISTS compound_cache (
    compound    TEXT    PRIMARY KEY,
    meaning     TEXT    NOT NULL,
    created_at  INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS rate_limits (
    device_id   TEXT    NOT NULL,
    date        TEXT    NOT NULL,
    count       INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (device_id, date)
);

-- Caches in-context word-sense disambiguation results, keyed on
-- language + word + sentence. result is JSON: {"id": <number>, "meaning": "..."}.
CREATE TABLE IF NOT EXISTS disambiguation_cache (
    cache_key   TEXT    PRIMARY KEY,
    result      TEXT    NOT NULL,
    created_at  INTEGER NOT NULL
);
