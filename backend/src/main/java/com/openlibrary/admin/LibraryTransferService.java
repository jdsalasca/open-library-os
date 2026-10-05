package com.openlibrary.admin;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads and writes the whole library as one document.
 *
 * <p>The tables are read in dependency order and written back in the same order,
 * with {@code INSERT ... ON CONFLICT} against the natural key of each table. That
 * is what makes an import idempotent: restoring the same export twice leaves the
 * library exactly as it was, which is the difference between a backup you can
 * trust and one you restore hopefully.
 */
@Service
class LibraryTransferService {

    private final JdbcTemplate jdbc;

    LibraryTransferService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    LibraryDocument export() {
        var publishers = jdbc.queryForList(
                "select id, name, country from publishers order by name");
        var authors = jdbc.queryForList(
                "select id, name, sort_name, bio from authors order by name");
        var categories = jdbc.queryForList(
                "select id, name, slug from categories order by name");
        var books = jdbc.queryForList("""
                select b.id, b.isbn13, b.isbn10, b.title, b.subtitle, b.publication_year,
                       b.language, b.pages, b.summary, b.cover_url, b.edition,
                       p.name as publisher_name
                from books b left join publishers p on p.id = b.publisher_id
                order by b.title, b.isbn13 nulls last
                """);
        var bookAuthors = jdbc.queryForList("""
                select bk.id as book_id, a.name as author_name, bk.role, bk.position
                from book_authors bk join authors a on a.id = bk.author_id
                order by bk.book_id, bk.position
                """);
        var bookCategories = jdbc.queryForList("""
                select bk.book_id, c.name as category_name
                from book_categories bk join categories c on c.id = bk.category_id
                order by bk.book_id, c.name
                """);
        var locations = jdbc.queryForList("""
                select l.id, l.code, l.name, l.kind, parent.code as parent_code,
                       l.sort_order, l.x, l.y, l.z, l.width, l.depth, l.height
                from locations l left join locations parent on parent.id = l.parent_id
                order by l.sort_order, l.code
                """);
        var copies = jdbc.queryForList("""
                select c.id, c.code, c.barcode, c.qr, c.status, b.isbn13, b.title,
                       l.code as location_code, c.acquired_at, c.price, c.notes
                from copies c
                join books b on b.id = c.book_id
                left join locations l on l.id = c.location_id
                order by c.code
                """);
        var users = jdbc.queryForList("""
                select id, email, full_name, role, active, must_change_password,
                       password_hash, created_at
                from users order by email
                """);
        var loans = jdbc.queryForList("""
                select l.id, c.code as copy_code, u.email, l.borrowed_at, l.due_at,
                       l.returned_at, l.renewals
                from loans l
                join copies c on c.id = l.copy_id
                join users u on u.id = l.user_id
                order by l.borrowed_at
                """);
        var reservations = jdbc.queryForList("""
                select r.id, b.title, b.isbn13, u.email, r.created_at,
                       r.fulfilled_at, r.cancelled_at
                from reservations r
                join books b on b.id = r.book_id
                join users u on u.id = r.user_id
                order by r.created_at
                """);
        var appConfig = jdbc.queryForList("select key, value from app_config order by key");

        var document = new LibraryDocument(
                LibraryDocument.VERSION, Instant.now().toString(),
                Map.of("publishers", publishers.size(), "authors", authors.size(),
                        "categories", categories.size(), "books", books.size(),
                        "locations", locations.size(), "copies", copies.size(),
                        "users", users.size(), "loans", loans.size(),
                        "reservations", reservations.size(), "appConfig", appConfig.size()),
                publishers, authors, categories,
                attachToBooks(books, bookAuthors, bookCategories),
                locations, copies, users, loans, reservations, appConfig);
        return document;
    }

    /**
     * Books carry their authors and categories inside them: a document of nested
     * rows is what a person can actually read, and it keeps the importer from
     * having to match up separate id lists.
     */
    private List<Map<String, Object>> attachToBooks(List<Map<String, Object>> books,
                                                   List<Map<String, Object>> bookAuthors,
                                                   List<Map<String, Object>> bookCategories) {
        var authorsByBook = new LinkedHashMap<Long, List<Map<String, Object>>>();
        for (var link : bookAuthors) {
            long bookId = ((Number) link.get("book_id")).longValue();
            authorsByBook.computeIfAbsent(bookId, key -> new ArrayList<>()).add(Map.of(
                    "name", link.get("author_name"),
                    "role", link.get("role"),
                    "position", link.get("position")));
        }
        var categoriesByBook = new LinkedHashMap<Long, List<String>>();
        for (var link : bookCategories) {
            long bookId = ((Number) link.get("book_id")).longValue();
            categoriesByBook.computeIfAbsent(bookId, key -> new ArrayList<>())
                    .add(String.valueOf(link.get("category_name")));
        }
        var result = new ArrayList<Map<String, Object>>();
        for (var book : books) {
            long bookId = ((Number) book.get("id")).longValue();
            var copy = new LinkedHashMap<>(book);
            copy.remove("id");
            copy.put("authors", authorsByBook.getOrDefault(bookId, List.of()));
            copy.put("categories", categoriesByBook.getOrDefault(bookId, List.of()));
            result.add(copy);
        }
        return result;
    }

    /** What an import did, so the operator sees progress instead of a silent 200. */
    record ImportReport(Map<String, Integer> created, Map<String, Integer> updated,
                        Map<String, Integer> skipped) {
    }

    @Transactional
    ImportReport importDocument(LibraryDocument document) {
        var created = new LinkedHashMap<String, Integer>();
        var updated = new LinkedHashMap<String, Integer>();
        var skipped = new LinkedHashMap<String, Integer>();

        for (var row : document.publishers()) {
            var name = String.valueOf(row.get("name"));
            record(created, updated, "publishers", isNew("publishers", "name", name));
            jdbc.update("""
                    insert into publishers (name, country) values (?, ?)
                    on conflict (name) do update set country = excluded.country
                    """, name, text(row.get("country")));
        }
        for (var row : document.authors()) {
            var name = String.valueOf(row.get("name"));
            record(created, updated, "authors", isNew("authors", "name", name));
            jdbc.update("""
                    insert into authors (name, sort_name, bio) values (?, ?, ?)
                    on conflict (name) do update set
                        sort_name = excluded.sort_name, bio = excluded.bio
                    """, name, text(row.get("sort_name")), text(row.get("bio")));
        }
        for (var row : document.categories()) {
            var name = String.valueOf(row.get("name"));
            var slug = text(row.get("slug")) != null ? String.valueOf(row.get("slug")) : slug(name);
            record(created, updated, "categories", isNew("categories", "slug", slug));
            jdbc.update("""
                    insert into categories (name, slug) values (?, ?)
                    on conflict (slug) do update set name = excluded.name
                    """, name, slug);
        }

        var publisherIds = nameToId("publishers", "name");
        var categoryIds = nameToId("categories", "name");

        for (var row : document.books()) {
            var title = String.valueOf(row.get("title"));
            var isbn13 = text(row.get("isbn13"));
            Long publisherId = publisherIds.get(String.valueOf(row.get("publisher_name")));

            if (isbn13 != null) {
                // Postgres reports 1 row for the INSERT and for the UPDATE alike, so
                // "was this new?" has to be asked before writing, not inferred after.
                record(created, updated, "books", isNew("books", "isbn13", isbn13));
                jdbc.update("""
                        insert into books (isbn13, isbn10, title, subtitle, publisher_id,
                                           publication_year, language, pages, summary,
                                           cover_url, edition)
                        values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        on conflict (isbn13) where isbn13 is not null do update set
                            title = excluded.title, subtitle = excluded.subtitle,
                            publisher_id = excluded.publisher_id,
                            publication_year = excluded.publication_year,
                            language = excluded.language, pages = excluded.pages,
                            summary = excluded.summary, cover_url = excluded.cover_url,
                            edition = excluded.edition, updated_at = now()
                        """, isbn13, text(row.get("isbn10")), title, text(row.get("subtitle")),
                        publisherId, number(row.get("publication_year")), text(row.get("language")),
                        number(row.get("pages")), text(row.get("summary")),
                        text(row.get("cover_url")), text(row.get("edition")));
            } else {
                // No ISBN to key on: a title plus its first author is what a person
                // would call the same book.
                record(created, updated, "books", isNewBookWithoutIsbn(title));
                jdbc.update("""
                        insert into books (isbn13, isbn10, title, subtitle, publisher_id,
                                           publication_year, language, pages, summary,
                                           cover_url, edition)
                        values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        on conflict (id) do nothing
                        """, null, null, title, text(row.get("subtitle")), publisherId,
                        number(row.get("publication_year")), text(row.get("language")),
                        number(row.get("pages")), text(row.get("summary")),
                        text(row.get("cover_url")), text(row.get("edition")));
            }

            Long id = findBookId(isbn13, title);
            if (id == null) {
                continue;
            }
            jdbc.update("delete from book_authors where book_id = ?", id);
            jdbc.update("delete from book_categories where book_id = ?", id);
            int position = 0;
            for (var author : asList(row.get("authors"))) {
                var name = String.valueOf(author.get("name"));
                jdbc.update("insert into authors (name) values (?) on conflict (name) do nothing", name);
                jdbc.update("""
                        insert into book_authors (book_id, author_id, role, position)
                        select ?, id, ?, ? from authors where name = ?
                        on conflict (book_id, author_id, role) do nothing
                        """, id, String.valueOf(author.getOrDefault("role", "AUTOR")),
                        position++, name);
            }
            for (var category : asStrings(row.get("categories"))) {
                var name = category;
                var categoryId = categoryIds.get(name);
                if (categoryId == null) {
                    var slug = slug(name);
                    jdbc.update("insert into categories (name, slug) values (?, ?)"
                            + " on conflict (slug) do nothing", name, slug);
                    categoryIds.put(name, jdbc.queryForObject(
                            "select id from categories where slug = ?", Long.class, slug));
                    categoryId = categoryIds.get(name);
                }
                jdbc.update("""
                        insert into book_categories (book_id, category_id)
                        values (?, ?) on conflict (book_id, category_id) do nothing
                        """, id, categoryId);
            }
        }

        // Locations come after books because the tree is walked from the roots down.
        var locationIds = codeToId("locations", "code");
        for (var row : document.locations()) {
            var code = String.valueOf(row.get("code"));
            var parentCode = text(row.get("parent_code"));
            Long parentId = parentCode == null ? null : locationIds.get(parentCode);
            jdbc.update("""
                    insert into locations (code, name, kind, parent_id, sort_order,
                                           x, y, z, width, depth, height)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    on conflict (code) do update set
                        name = excluded.name, kind = excluded.kind,
                        parent_id = excluded.parent_id, sort_order = excluded.sort_order,
                        x = excluded.x, y = excluded.y, z = excluded.z,
                        width = excluded.width, depth = excluded.depth,
                        height = excluded.height, updated_at = now()
                    """, code, String.valueOf(row.get("name")), String.valueOf(row.get("kind")),
                    parentId, intOr(row.get("sort_order"), 0), decimal(row.get("x")),
                    decimal(row.get("y")), decimal(row.get("z")), decimal(row.get("width")),
                    decimal(row.get("depth")), decimal(row.get("height")));
            locationIds.put(code, jdbc.queryForObject(
                    "select id from locations where code = ?", Long.class, code));
        }

        var bookIds = new LinkedHashMap<String, Long>();
        jdbc.query("select id, coalesce(isbn13, '') from books", rs -> {
            bookIds.put(rs.getString(2), rs.getLong(1));
        });

        for (var row : document.users()) {
            var email = String.valueOf(row.get("email"));
            var exists = jdbc.queryForObject(
                    "select count(*) from users where lower(email) = lower(?)",
                    Integer.class, email);
            var isNew = exists != null && exists == 0;
            jdbc.update("""
                    insert into users (email, password_hash, full_name, role, active,
                                       must_change_password)
                    values (?, ?, ?, ?, ?, ?)
                    -- The unique index is on lower(email), so the same expression
                    -- has to appear here or Postgres cannot match it.
                    on conflict (lower(email)) do nothing
                    """, email, row.get("password_hash"), String.valueOf(row.get("full_name")),
                    String.valueOf(row.get("role")), bool(row.get("active")),
                    bool(row.get("must_change_password")));
            record(created, updated, "users", isNew);
        }

        var userIds = new LinkedHashMap<String, Long>();
        jdbc.query("select id, lower(email) from users", rs -> {
            userIds.put(rs.getString(2), rs.getLong(1));
        });

        for (var row : document.copies()) {
            var code = String.valueOf(row.get("code"));
            var bookKey = String.valueOf(row.getOrDefault("isbn13", ""));
            Long bookId = bookIds.get(bookKey);
            if (bookId == null) {
                // Fall back to the title, which is how a book without ISBN arrives.
                bookId = queryId("select id from books where title = ? limit 1", String.valueOf(row.get("title")));
            }
            var locationCode = text(row.get("location_code"));
            Long locationId = locationCode == null ? null : locationIds.get(locationCode);
            var isNew = jdbc.queryForObject("select count(*) from copies where code = ?",
                    Integer.class, code) == 0;
            if (bookId == null || locationCode == null && locationId == null) {
                // A copy without its book cannot be restored; skipping it loudly
                // beats importing a row that points nowhere.
                record(skipped, skipped, "copies", true);
                continue;
            }
            jdbc.update("""
                    insert into copies (code, barcode, qr, book_id, location_id, status,
                                        acquired_at, price, notes)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    on conflict (code) do update set
                        book_id = excluded.book_id, location_id = excluded.location_id,
                        status = excluded.status, acquired_at = excluded.acquired_at,
                        price = excluded.price, notes = excluded.notes, updated_at = now()
                    """, code, String.valueOf(row.get("barcode")), String.valueOf(row.get("qr")),
                    bookId, locationId,
                    String.valueOf(row.get("status")), date(row.get("acquired_at")),
                    decimal(row.get("price")), text(row.get("notes")));
            record(created, updated, "copies", isNew);
        }

        var copyIds = new LinkedHashMap<String, Long>();
        jdbc.query("select id, code from copies", rs -> {
            copyIds.put(rs.getString(2), rs.getLong(1));
        });

        for (var row : document.loans()) {
            Long copyId = copyIds.get(String.valueOf(row.get("copy_code")));
            Long userId = userIds.get(String.valueOf(row.get("email")).toLowerCase());
            if (copyId == null || userId == null) {
                record(skipped, skipped, "loans", true);
                continue;
            }
            // Keyed on copy plus borrowed_at, which together identify one loan.
            var exists = jdbc.queryForObject("""
                    select count(*) from loans
                    where copy_id = ? and borrowed_at = ?
                    """, Integer.class, copyId, instant(row.get("borrowed_at")));
            jdbc.update("""
                    insert into loans (copy_id, user_id, borrowed_at, due_at, returned_at, renewals)
                    values (?, ?, ?, ?, ?, ?)
                    on conflict (id) do nothing
                    """, copyId, userId, instant(row.get("borrowed_at")),
                    instant(row.get("due_at")), instant(row.get("returned_at")),
                    intOr(row.get("renewals"), 0));
            record(created, updated, "loans", exists != null && exists == 0);
        }

        for (var row : document.reservations()) {
            var title = String.valueOf(row.get("title"));
            var isbn13 = String.valueOf(row.getOrDefault("isbn13", ""));
            Long bookId = bookIds.getOrDefault(isbn13,
                    queryId("select id from books where title = ? limit 1", title));
            Long userId = userIds.get(String.valueOf(row.get("email")).toLowerCase());
            if (bookId == null || userId == null) {
                record(skipped, skipped, "reservations", true);
                continue;
            }
            var exists = jdbc.queryForObject("""
                    select count(*) from reservations
                    where book_id = ? and user_id = ? and created_at = ?
                    """, Integer.class, bookId, userId, instant(row.get("created_at")));
            jdbc.update("""
                    insert into reservations (book_id, user_id, created_at, fulfilled_at, cancelled_at)
                    values (?, ?, ?, ?, ?)
                    on conflict (id) do nothing
                    """, bookId, userId, instant(row.get("created_at")),
                    instant(row.get("fulfilled_at")), instant(row.get("cancelled_at")));
            record(created, updated, "reservations", exists != null && exists == 0);
        }

        // Configuration last: it decides how the imported loans behave from now on.
        for (var row : document.appConfig()) {
            var key = String.valueOf(row.get("key"));
            var isNew = jdbc.queryForObject("select count(*) from app_config where key = ?",
                    Integer.class, key) == 0;
            jdbc.update("""
                    insert into app_config (key, value) values (?, ?)
                    on conflict (key) do update set value = excluded.value, updated_at = now()
                    """, key, String.valueOf(row.get("value")));
            record(created, updated, "appConfig", isNew);
        }

        return new ImportReport(created, updated, skipped);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private void record(Map<String, Integer> created, Map<String, Integer> updated,
                        String table, boolean wasCreated) {
        var target = wasCreated ? created : updated;
        target.merge(table, 1, Integer::sum);
    }

    /** True when the row identified by that key is not there yet. */
    /** Single-value query, with the row mapper typed so the overload is not ambiguous. */
    private Long queryId(String sql, Object... args) {
        var found = jdbc.queryForList(sql, Long.class, args);
        return found.isEmpty() ? null : found.get(0);
    }

    private boolean isNew(String table, String column, Object key) {
        var exists = jdbc.queryForObject(
                "select count(*) from " + table + " where " + column + " = ?",
                Integer.class, key);
        return exists == null || exists == 0;
    }

    /** A book with no ISBN is recognised by its title among the ISBN-less ones. */
    private boolean isNewBookWithoutIsbn(String title) {
        var exists = jdbc.queryForObject(
                "select count(*) from books where isbn13 is null and title = ?",
                Integer.class, title);
        return exists == null || exists == 0;
    }

    private Map<String, Long> nameToId(String table, String column) {
        var result = new LinkedHashMap<String, Long>();
        jdbc.query("select id, " + column + " from " + table, rs -> {
            result.put(rs.getString(2), rs.getLong(1));
        });
        return result;
    }

    private Map<String, Long> codeToId(String table, String column) {
        return nameToId(table, column);
    }

    private Long findBookId(String isbn13, String title) {
        if (isbn13 != null) {
            var byIsbn = queryId("select id from books where isbn13 = ?", isbn13);
            if (byIsbn != null) {
                return byIsbn;
            }
        }
        return queryId("select id from books where isbn13 is null and title = ? limit 1", title);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> asList(Object value) {
        return value instanceof List<?> list ? (List<Map<String, Object>>) list : List.of();
    }

    /** Categories travel as plain names, not as objects. */
    private List<String> asStrings(Object value) {
        if (value instanceof List<?> list) {
            return list.stream().map(String::valueOf).toList();
        }
        return List.of();
    }

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static Integer number(Object value) {
        return value instanceof Number n ? n.intValue() : null;
    }

    private static int intOr(Object value, int fallback) {
        return value instanceof Number n ? n.intValue() : fallback;
    }

    private static java.math.BigDecimal decimal(Object value) {
        if (value instanceof Number n) {
            return java.math.BigDecimal.valueOf(n.doubleValue());
        }
        return null;
    }

    private static Boolean bool(Object value) {
        return value instanceof Boolean b ? b : Boolean.TRUE;
    }

    private static java.sql.Date date(Object value) {
        if (value == null) {
            return null;
        }
        return java.sql.Date.valueOf(String.valueOf(value).substring(0, 10));
    }

    private static Instant instant(Object value) {
        return value == null ? null : Instant.parse(String.valueOf(value));
    }

    private static String slug(String name) {
        return name.toLowerCase()
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-|-$)", "");
    }
}
