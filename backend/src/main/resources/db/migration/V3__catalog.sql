-- Catalogue: books with any number of authors, plus the axes a library filters by.
--
-- Authors, publishers and categories are referenced by *name* from the API: the
-- staff types "Gabriel Garcia Marquez" and the catalogue links the existing row
-- or creates it. That keeps the UI free of three separate admin screens.

CREATE TABLE publishers (
    id      BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name    TEXT NOT NULL UNIQUE,
    country TEXT
);

CREATE TABLE authors (
    id        BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name      TEXT NOT NULL UNIQUE,
    sort_name TEXT,
    bio       TEXT
);

CREATE TABLE categories (
    id        BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name      TEXT NOT NULL,
    slug      TEXT NOT NULL UNIQUE,
    parent_id BIGINT REFERENCES categories (id) ON DELETE SET NULL
);

CREATE TABLE books (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    isbn13           VARCHAR(13),
    isbn10           VARCHAR(10),
    title            TEXT NOT NULL,
    subtitle         TEXT,
    publisher_id     BIGINT      REFERENCES publishers (id) ON DELETE SET NULL,
    publication_year INT         CHECK (publication_year IS NULL OR publication_year BETWEEN 1450 AND 2200),
    language         TEXT,
    pages            INT         CHECK (pages IS NULL OR pages > 0),
    summary          TEXT,
    cover_url        TEXT,
    edition          TEXT,
    -- Lower-cased, accent-free copy of everything the staff would search by.
    -- Postgres has no built-in unaccent, and this keeps "marquez" finding "Márquez"
    -- without an extension or a join on every keystroke.
    search_text      TEXT,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- An ISBN identifies one edition, so it must be unique when present.
CREATE UNIQUE INDEX books_isbn13_key ON books (isbn13) WHERE isbn13 IS NOT NULL;
CREATE INDEX books_isbn10_idx ON books (isbn10) WHERE isbn10 IS NOT NULL;
CREATE INDEX books_title_idx ON books (lower(title));
CREATE INDEX books_search_idx ON books (search_text);
CREATE INDEX books_publisher_idx ON books (publisher_id);
CREATE INDEX books_year_idx ON books (publication_year);
CREATE INDEX books_language_idx ON books (language);

CREATE TABLE book_authors (
    id       BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    book_id  BIGINT NOT NULL REFERENCES books (id) ON DELETE CASCADE,
    author_id BIGINT NOT NULL REFERENCES authors (id) ON DELETE RESTRICT,
    role     TEXT    NOT NULL DEFAULT 'AUTOR'
        CHECK (role IN ('AUTOR', 'COAUTOR', 'TRADUCTOR', 'ILUSTRADOR', 'EDITOR')),
    position INT     NOT NULL DEFAULT 0,
    -- A person appears once per role: "Frank Herbert" as AUTOR and as EDITOR is fine.
    UNIQUE (book_id, author_id, role)
);

CREATE INDEX book_authors_author_idx ON book_authors (author_id);

CREATE TABLE book_categories (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    book_id     BIGINT NOT NULL REFERENCES books (id) ON DELETE CASCADE,
    category_id BIGINT NOT NULL REFERENCES categories (id) ON DELETE CASCADE,
    UNIQUE (book_id, category_id)
);

CREATE INDEX book_categories_category_idx ON book_categories (category_id);