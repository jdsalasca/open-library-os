-- Physical copies: N per book, each with its own scannable code and a place on
-- the shelf. Locations carry x/y/z/size so round 6 can draw the same data in 3D
-- without a second source of truth.

CREATE TABLE locations (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code       TEXT        NOT NULL UNIQUE,
    name       TEXT        NOT NULL,
    kind       TEXT        NOT NULL
        CHECK (kind IN ('SALA', 'PASILLO', 'ESTANTE', 'DEPOSITO')),
    parent_id  BIGINT      REFERENCES locations (id) ON DELETE RESTRICT,
    sort_order INT         NOT NULL DEFAULT 0,
    -- Metres, with the origin at the entrance. Nullable so a library can register
    -- its shelves before anyone measures the room.
    x          NUMERIC(8, 3),
    y          NUMERIC(8, 3),
    z          NUMERIC(8, 3),
    width      NUMERIC(8, 3),
    depth      NUMERIC(8, 3),
    height     NUMERIC(8, 3),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX locations_parent_idx ON locations (parent_id, sort_order);

CREATE TABLE copies (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    book_id     BIGINT      NOT NULL REFERENCES books (id) ON DELETE RESTRICT,
    code        TEXT        NOT NULL UNIQUE,
    barcode     TEXT        NOT NULL UNIQUE,
    qr          TEXT        NOT NULL UNIQUE,
    location_id BIGINT      REFERENCES locations (id) ON DELETE SET NULL,
    status      TEXT        NOT NULL DEFAULT 'DISPONIBLE'
        CHECK (status IN ('DISPONIBLE', 'PRESTADO', 'MANTENIMIENTO', 'PERDIDO')),
    acquired_at DATE,
    price       NUMERIC(10, 2),
    notes       TEXT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX copies_book_idx ON copies (book_id);
CREATE INDEX copies_location_idx ON copies (location_id);
CREATE INDEX copies_status_idx ON copies (status);

-- Where a copy has been. Kept as a table instead of a column on copies because the
-- 3D map and the "who moved what" audit both need the history, not just the place.
CREATE TABLE copy_moves (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    copy_id         BIGINT      NOT NULL REFERENCES copies (id) ON DELETE CASCADE,
    from_location_id BIGINT     REFERENCES locations (id) ON DELETE SET NULL,
    to_location_id  BIGINT      REFERENCES locations (id) ON DELETE SET NULL,
    moved_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    user_id         BIGINT      REFERENCES users (id) ON DELETE SET NULL
);

CREATE INDEX copy_moves_copy_idx ON copy_moves (copy_id, moved_at DESC);

-- One place to hand out the next sequence number without a race between two
-- librarians cataloguing at the same time.
CREATE SEQUENCE copy_code_sequence START 1;