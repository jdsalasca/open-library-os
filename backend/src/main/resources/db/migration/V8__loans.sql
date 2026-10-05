-- Round 4: loans and reservations. A book has many copies and a copy is the
-- thing that travels, so loans point at copies and never at books.
CREATE TABLE loans (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    copy_id     BIGINT       NOT NULL REFERENCES copies (id) ON DELETE RESTRICT,
    user_id     BIGINT       NOT NULL REFERENCES users (id) ON DELETE RESTRICT,
    borrowed_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    due_at      TIMESTAMPTZ  NOT NULL,
    returned_at TIMESTAMPTZ,
    renewals    INTEGER      NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- One copy can only be on one loan at a time. The partial unique index is the
-- real guard: two concurrent checkouts of the same copy cannot both commit.
CREATE UNIQUE INDEX loans_one_active_per_copy ON loans (copy_id) WHERE returned_at IS NULL;
CREATE INDEX loans_user_idx ON loans (user_id, borrowed_at DESC);
CREATE INDEX loans_open_idx ON loans (due_at) WHERE returned_at IS NULL;

CREATE TABLE reservations (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    book_id      BIGINT      NOT NULL REFERENCES books (id) ON DELETE CASCADE,
    user_id      BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    fulfilled_at TIMESTAMPTZ,
    cancelled_at TIMESTAMPTZ
);

-- The queue is ordered by creation, so the same pair can only wait once at a time.
CREATE UNIQUE INDEX reservations_one_open_per_reader ON reservations (book_id, user_id)
    WHERE fulfilled_at IS NULL AND cancelled_at IS NULL;
CREATE INDEX reservations_queue_idx ON reservations (book_id, created_at);

-- Loan periods live in the app_config seeded by V1, so a library can change them
-- without a redeploy: loans.days_default, loans.max_active_per_reader and
-- loans.max_renewals. Nothing to insert here.
