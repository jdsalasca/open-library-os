-- ISBN autofill cache. Answers from outside APIs are stored so the same book is
-- never fetched twice, and so a miss is remembered instead of being retried on
-- every keystroke.
--
-- V6 keeps the number the plan reserves for the ISBN round; V2..V5 belong to the
-- auth, catalogue, inventory and loans rounds.

CREATE TABLE isbn_cache (
    isbn       TEXT        PRIMARY KEY,
    -- 'openlibrary', 'googlebooks'… so the UI can say where each field came from.
    source     TEXT        NOT NULL,
    -- The normalised ExternalBook as JSONB, or the JSON literal null when the
    -- provider had nothing. A miss is cached too: without it, a librarian typing
    -- an unknown ISBN would hit the API on every attempt.
    payload    JSONB       NOT NULL,
    fetched_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX isbn_cache_fetched_at_idx ON isbn_cache (fetched_at DESC);
