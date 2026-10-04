package com.openlibrary.isbn;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

import java.sql.Types;
import java.util.Optional;

/**
 * Postgres-backed cache for ISBN lookups.
 *
 * <p>A hand-rolled repository over JdbcTemplate: one table, one query and one insert,
 * and it stores exactly the {@code ExternalBook} shape. A JPA entity would add a
 * second model to keep in sync for no benefit.
 */
@Repository
public class IsbnCache {

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public IsbnCache(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /**
     * @return the cached answer, or empty when nothing is cached. A cached miss also
     *         comes back empty, so callers use {@link #isCached} to tell "never asked"
     *         from "asked and nobody knew".
     */
    public Optional<ExternalBook> get(String isbn) {
        Optional<String> row = Optional.ofNullable(jdbc.queryForObject(
                "SELECT payload::text FROM isbn_cache WHERE isbn = ?",
                (rs, i) -> rs.getString(1), isbn));
        return row.map(this::toBook);
    }

    public boolean isCached(String isbn) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM isbn_cache WHERE isbn = ?", Integer.class, isbn);
        return count != null && count > 0;
    }

    /** Stores an answer, or the absence of one, so it is not asked for again. */
    public void put(String isbn, String source, ExternalBook book) {
        String payload = book == null ? "null" : json.writeValueAsString(book);
        // The column is jsonb and the driver binds a String as varchar, so the type has
        // to be declared explicitly; a "?::jsonb" cast in the SQL is not enough because
        // the parameter is already typed as text by then.
        jdbc.update("""
                INSERT INTO isbn_cache (isbn, source, payload, fetched_at)
                VALUES (?, ?, ?, now())
                ON CONFLICT (isbn) DO UPDATE
                    SET source = EXCLUDED.source,
                        payload = EXCLUDED.payload,
                        fetched_at = EXCLUDED.fetched_at
                """,
                ps -> {
                    ps.setString(1, isbn);
                    ps.setString(2, source);
                    ps.setObject(3, payload, Types.OTHER);
                });
    }

    private ExternalBook toBook(String payload) {
        if (payload == null || "null".equals(payload.trim())) {
            return null;
        }
        return json.readValue(payload, ExternalBook.class);
    }
}
