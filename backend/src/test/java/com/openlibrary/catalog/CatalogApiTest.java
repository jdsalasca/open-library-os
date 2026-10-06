package com.openlibrary.catalog;

import com.openlibrary.PostgresTest;
import com.openlibrary.auth.Role;
import com.openlibrary.support.DemoUsers;
import com.openlibrary.support.HttpTestClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CatalogApiTest extends PostgresTest {

    @LocalServerPort
    int port;

    @Autowired
    DataSource dataSource;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PasswordEncoder passwords;

    private HttpTestClient reader;
    private HttpTestClient admin;

    @BeforeEach
    void setUp() {
        DemoUsers.seed(dataSource, passwords);
        DemoUsers.resetPasswords(jdbc, passwords);
        jdbc.update("update users set must_change_password = false");
        // The container is shared by every test class, so anything that points at a
        // book has to go first: copies RESTRICT the delete of their book.
        jdbc.update("delete from reservations");
        jdbc.update("delete from loans");
        jdbc.update("delete from copy_moves");
        jdbc.update("delete from copies");
        jdbc.update("delete from locations");
        jdbc.update("delete from book_categories");
        jdbc.update("delete from book_authors");
        jdbc.update("delete from books");
        jdbc.update("delete from categories");
        jdbc.update("delete from authors");
        jdbc.update("delete from publishers");

        reader = signedIn(Role.LECTOR);
        admin = signedIn(Role.ADMINISTRATIVO);
    }

    private HttpTestClient signedIn(Role role) {
        var client = new HttpTestClient(port);
        assertThat(client.post("/auth/login", Map.of(
                "email", DemoUsers.emailFor(role),
                "password", DemoUsers.passwordFor(DemoUsers.emailFor(role)))).status())
                .as("login as %s", role).isEqualTo(200);
        return client;
    }

    private Map<String, Object> book(String title, String isbn, List<Map<String, Object>> authors) {
        return Map.of(
                "title", title,
                "isbn", isbn,
                "authors", authors,
                "categories", List.of("Historia"),
                "publisher", "Taurus",
                "publicationYear", 2001,
                "language", "es",
                "pages", 432);
    }

    private Long categoryId(String name) {
        for (var c : admin.get("/catalog/categories").json()) {
            if (c.path("name").asText().equals(name)) {
                return c.path("id").asLong();
            }
        }
        throw new IllegalStateException("no category " + name);
    }

    private Long publisherId(String name) {
        for (var p : admin.get("/catalog/publishers").json()) {
            if (p.path("name").asText().equals(name)) {
                return p.path("id").asLong();
            }
        }
        throw new IllegalStateException("no publisher " + name);
    }

    private Map<String, Object> author(String name) {
        return Map.of("name", name, "role", "AUTOR");
    }

    @Test
    void createsABookWithSeveralAuthorsInOrder() {
        var created = admin.post("/catalog/books", book(
                "Historia del tiempo", "9780306406157",
                List.of(author("Stephen Hawking"), author("Jane Doe"))));

        assertThat(created.status()).isEqualTo(201);
        assertThat(created.text("title")).isEqualTo("Historia del tiempo");
        assertThat(created.json().path("authors")).hasSize(2);
        assertThat(created.json().path("authors").get(0).path("name").asText())
                .isEqualTo("Stephen Hawking");

        Integer linked = jdbc.queryForObject("select count(*) from book_authors", Integer.class);
        assertThat(linked).isEqualTo(2);
    }

    @Test
    void reusesAnExistingAuthorInsteadOfDuplicatingIt() {
        admin.post("/catalog/books", book("Uno", "9780306406157", List.of(author("Ada Lovelace"))));
        admin.post("/catalog/books", book("Dos", "9788491058106", List.of(author("Ada Lovelace"))));

        Integer authors = jdbc.queryForObject("select count(*) from authors", Integer.class);
        Integer links = jdbc.queryForObject("select count(*) from book_authors", Integer.class);

        assertThat(authors).as("one row per author name").isEqualTo(1);
        assertThat(links).isEqualTo(2);
    }

    @Test
    void rejectsAnInvalidIsbnWithAFieldError() {
        var created = admin.post("/catalog/books", book("Malo", "123", List.of(author("X"))));

        assertThat(created.status()).isEqualTo(400);
        assertThat(created.body()).contains("isbn");
    }

    @Test
    void rejectsTwoBooksWithTheSameIsbn() {
        admin.post("/catalog/books", book("Uno", "9780306406157", List.of(author("A"))));
        var duplicate = admin.post("/catalog/books", book("Otro", "9780306406157", List.of(author("B"))));

        assertThat(duplicate.status()).isEqualTo(409);
        assertThat(duplicate.body()).contains("isbn");
    }

    @Test
    void treatsAnIsbn10AsTheSameBookAsItsIsbn13Form() {
        admin.post("/catalog/books", book("Uno", "9780306406157", List.of(author("A"))));
        var same = admin.post("/catalog/books", book("Otro", "0-306-40615-2", List.of(author("B"))));

        assertThat(same.status()).isEqualTo(409);
    }

    @Test
    void allowsDifferentEditionsOfTheSameBook() {
        admin.post("/catalog/books", Map.of(
                "title", "Dune", "isbn", "9780306406157", "authors", List.of(author("Frank Herbert"))));
        var second = admin.post("/catalog/books", Map.of(
                "title", "Dune", "isbn", "9788491058106", "edition", "4a edicion",
                "authors", List.of(author("Frank Herbert"))));

        assertThat(second.status()).isEqualTo(201);
    }

    @Test
    void findsABookByTitleByAuthorAndByIsbn() {
        admin.post("/catalog/books", book("Historia del tiempo", "9780306406157",
                List.of(author("Stephen Hawking"))));

        assertThat(reader.get("/catalog/books?q=historia").json().path("totalElements").asInt())
                .isEqualTo(1);
        assertThat(reader.get("/catalog/books?q=hawking").json().path("totalElements").asInt())
                .isEqualTo(1);
        assertThat(reader.get("/catalog/books?q=9780306406157").json().path("totalElements").asInt())
                .isEqualTo(1);
        assertThat(reader.get("/catalog/books?q=no-existe").json().path("totalElements").asInt())
                .isZero();
    }

    @Test
    void ignoresCaseAndAccentsWhenSearching() {
        admin.post("/catalog/books", book("Cien años de soledad", "9780306406157",
                List.of(author("Gabriel García Márquez"))));

        assertThat(reader.get("/catalog/books?q=CIEN ANOS").json().path("totalElements").asInt())
                .isEqualTo(1);
        assertThat(reader.get("/catalog/books?q=garcia marquez").json().path("totalElements").asInt())
                .isEqualTo(1);
    }

    @Test
    void filtersByCategoryYearLanguageAndPublisher() {
        admin.post("/catalog/books", book("Historia del tiempo", "9780306406157",
                List.of(author("Stephen Hawking"))));
        admin.post("/catalog/books", Map.of(
                "title", "Neuromancer", "isbn", "9788491058106",
                "authors", List.of(author("William Gibson")),
                "categories", List.of("Tecnologia"),
                "publisher", "Grijalbo", "publicationYear", 1984, "language", "en"));

        var all = reader.get("/catalog/books");
        assertThat(all.json().path("totalElements").asInt()).isEqualTo(2);

        var byCategory = reader.get("/catalog/books?category=" + categoryId("Historia"));
        assertThat(byCategory.json().path("totalElements").asInt()).isEqualTo(1);
        assertThat(byCategory.json().path("content").get(0).path("title").asText())
                .isEqualTo("Historia del tiempo");

        assertThat(reader.get("/catalog/books?year=1984").json().path("totalElements").asInt())
                .isEqualTo(1);
        assertThat(reader.get("/catalog/books?language=en").json().path("totalElements").asInt())
                .isEqualTo(1);
        assertThat(reader.get("/catalog/books?publisher=" + publisherId("Grijalbo")).json().path("totalElements").asInt())
                .isEqualTo(1);
    }

    @Test
    void combinesFilters() {
        admin.post("/catalog/books", book("Historia del tiempo", "9780306406157",
                List.of(author("Stephen Hawking"))));
        admin.post("/catalog/books", Map.of(
                "title", "Historia del tiempo", "isbn", "9788491058106",
                "authors", List.of(author("Stephen Hawking")),
                "categories", List.of("Historia"), "publicationYear", 2005));
        assertThat(reader.get("/catalog/books?category=" + categoryId("Historia") + "&year=2001").json()
                .path("totalElements").asInt()).isEqualTo(1);
        assertThat(reader.get("/catalog/books?category=" + categoryId("Historia") + "&year=1999").json()
                .path("totalElements").asInt()).isZero();
    }

    @Test
    void paginatesAndSorts() {
        // No ISBN: this test is about paging, and invented ISBNs would fail the
        // checksum before reaching the assertion.
        for (int i = 1; i <= 5; i++) {
            admin.post("/catalog/books", Map.of(
                    "title", "Libro " + i,
                    "authors", List.of(author("Autor " + i))));
        }

        // Pages are zero-based, so page=0 is the first two books.
        var first = reader.get("/catalog/books?size=2&page=0&sort=title:asc");
        assertThat(first.json().path("content")).hasSize(2);
        assertThat(first.json().path("totalElements").asInt()).isEqualTo(5);
        assertThat(first.json().path("totalPages").asInt()).isEqualTo(3);
        assertThat(first.json().path("content").get(0).path("title").asText())
                .isEqualTo("Libro 1");

        var second = reader.get("/catalog/books?size=2&page=1&sort=title:asc");
        assertThat(second.json().path("content").get(0).path("title").asText())
                .isEqualTo("Libro 3");

        var desc = reader.get("/catalog/books?sort=title:desc");
        assertThat(desc.json().path("content").get(0).path("title").asText())
                .isEqualTo("Libro 5");
    }

    @Test
    void sortsEverySupportedFieldInBothDirections() {
        // Numeric and date columns must not be wrapped in lower(); Postgres rejects that.
        admin.post("/catalog/books", Map.of("title", "Antiguo", "authors",
                List.of(author("A")), "publicationYear", 1990));
        admin.post("/catalog/books", Map.of("title", "Reciente", "authors",
                List.of(author("B")), "publicationYear", 2020));

        for (String sort : new String[]{"title:asc", "title:desc",
                "publicationYear:asc", "publicationYear:desc",
                "createdAt:asc", "createdAt:desc",
                "pages:asc", "pages:desc"}) {
            assertThat(reader.get("/catalog/books?sort=" + sort).status())
                    .as("sort=%s", sort).isEqualTo(200);
        }

        var byYear = reader.get("/catalog/books?sort=publicationYear:desc");
        assertThat(byYear.json().path("content").get(0).path("title").asText())
                .isEqualTo("Reciente");
    }

    @Test
    void ignoresAnUnknownSortInsteadOfFailing() {
        admin.post("/catalog/books", book("Uno", "9780306406157", List.of(author("A"))));

        assertThat(reader.get("/catalog/books?sort=%3Bdrop%20table%20books").status()).isEqualTo(200);
        assertThat(jdbc.queryForObject("select count(*) from books", Integer.class)).isEqualTo(1);
    }

    @Test
    void returnsTheDetailWithAuthorsAndCategories() {
        var id = admin.post("/catalog/books", book("Historia del tiempo", "9780306406157",
                List.of(author("Stephen Hawking")))).text("id");

        var detail = reader.get("/catalog/books/" + id);

        assertThat(detail.status()).isEqualTo(200);
        assertThat(detail.text("summary")).isEqualTo("");
        assertThat(detail.json().path("categories")).hasSize(1);
        assertThat(detail.text("publisher")).isEqualTo("Taurus");
    }

    @Test
    void updatesABookReplacingItsAuthorsAndCategories() {
        var id = admin.post("/catalog/books", book("Borrador", "9780306406157",
                List.of(author("Temporal")))).text("id");

        var updated = admin.put("/catalog/books/" + id, Map.of(
                "title", "Definitivo",
                "isbn", "9780306406157",
                "authors", List.of(author("Real"), author("Segundo")),
                "categories", List.of("Novela")));

        assertThat(updated.status()).isEqualTo(200);
        assertThat(updated.text("title")).isEqualTo("Definitivo");
        assertThat(updated.json().path("authors")).hasSize(2);
        assertThat(reader.get("/catalog/books/" + id).json().path("categories").get(0)
                .path("name").asText()).isEqualTo("Novela");

        Integer stale = jdbc.queryForObject(
                "select count(*) from book_authors ba join authors a on a.id = ba.author_id"
                        + " where ba.book_id = ? and a.name = 'Temporal'",
                Integer.class, Long.valueOf(id));
        assertThat(stale).as("the replaced author link is gone").isZero();
    }

    /**
     * Saving a book you did not change is the most ordinary thing a librarian does:
     * open it to fix a typo, save, done. It used to fail with a 500 because the
     * credit links were deleted and re-inserted against a UNIQUE constraint that
     * Hibernate's flush order trips over. The test above only covered the case where
     * the authors actually change, which is why this stayed hidden.
     */
    @Test
    void savingTheSameBookTwiceDoesNotBite() {
        var id = admin.post("/catalog/books", book("Rayuela", "9780306406157",
                List.of(author("Julio Cortazar")))).text("id");

        var second = admin.put("/catalog/books/" + id, Map.of(
                "title", "Rayuela",
                "isbn", "9780306406157",
                "authors", List.of(author("Julio Cortazar")),
                "categories", List.of("Novela")));

        assertThat(second.status()).as("body: %s", second.body()).isEqualTo(200);
        assertThat(second.json().path("authors")).hasSize(1);
        assertThat(jdbc.queryForObject("select count(*) from book_authors where book_id = ?",
                Integer.class, Long.valueOf(id)))
                .as("one credit row, not two").isEqualTo(1);
    }

    @Test
    void deletesABook() {
        var id = admin.post("/catalog/books", book("Temporal", "9780306406157",
                List.of(author("Temporal")))).text("id");

        assertThat(admin.delete("/catalog/books/" + id).status()).isEqualTo(204);
        assertThat(reader.get("/catalog/books/" + id).status()).isEqualTo(404);
    }

    @Test
    void readersCannotWriteTheCatalogue() {
        assertThat(reader.post("/catalog/books", book("No", "9780306406157", List.of(author("A"))))
                .status()).isEqualTo(403);
        assertThat(reader.put("/catalog/books/1", Map.of("title", "No")).status()).isEqualTo(403);
        assertThat(reader.delete("/catalog/books/1").status()).isEqualTo(403);
    }

    @Test
    void listsAuthorsCategoriesAndPublishersForThePickers() {
        admin.post("/catalog/books", book("Historia del tiempo", "9780306406157",
                List.of(author("Stephen Hawking"))));

        assertThat(admin.get("/catalog/authors").json().path(0).path("name").asText())
                .isEqualTo("Stephen Hawking");
        assertThat(admin.get("/catalog/categories").json().path(0).path("name").asText())
                .isEqualTo("Historia");
        assertThat(admin.get("/catalog/publishers").json().path(0).path("name").asText())
                .isEqualTo("Taurus");
    }

    @Test
    void booksNeedAtLeastOneAuthorAndATitle() {
        var noAuthors = admin.post("/catalog/books", Map.of("title", "Sin autor", "authors", List.of()));
        var noTitle = admin.post("/catalog/books", Map.of("authors", List.of(author("A"))));

        assertThat(noAuthors.status()).isEqualTo(400);
        assertThat(noAuthors.body()).contains("authors");
        assertThat(noTitle.status()).isEqualTo(400);
        assertThat(noTitle.body()).contains("title");
    }
}