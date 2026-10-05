package com.openlibrary.admin;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import javax.sql.DataSource;

import com.openlibrary.PostgresTest;
import com.openlibrary.auth.Role;
import com.openlibrary.support.DemoUsers;
import com.openlibrary.support.HttpTestClient;

/**
 * "Move the library to another machine" has to be provable, so these tests export
 * and then import into an emptied database and check that the library comes back.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ExportImportApiTest extends PostgresTest {

    @LocalServerPort
    int port;

    @Autowired
    DataSource dataSource;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PasswordEncoder passwords;

    private HttpTestClient admin;
    private Long bookId;

    @BeforeEach
    void setUp() {
        DemoUsers.seed(dataSource, passwords);
        DemoUsers.resetPasswords(jdbc, passwords);
        jdbc.update("update users set must_change_password = false");
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

        admin = new HttpTestClient(port);
        assertThat(admin.post("/auth/login", Map.of(
                "email", DemoUsers.ADMIN_EMAIL,
                "password", DemoUsers.ADMIN_PASSWORD)).status()).isEqualTo(200);

        var clerk = new HttpTestClient(port);
        assertThat(clerk.post("/auth/login", Map.of(
                "email", DemoUsers.CLERK_EMAIL,
                "password", DemoUsers.CLERK_PASSWORD)).status()).isEqualTo(200);
        var book = clerk.post("/catalog/books", Map.of(
                "title", "Neuromante",
                "isbn", "9788491058106",
                "publisher", "Grijalbo",
                "categories", List.of("Ciencia Ficción"),
                "authors", List.of(Map.of("name", "William Gibson", "role", "AUTOR"))));
        assertThat(book.status()).as("seed book: %s", book.body()).isEqualTo(201);
        bookId = Long.valueOf(book.text("id"));

        var room = librarian().post("/inventory/locations",
                Map.of("code", "SALA-1", "name", "Sala", "kind", "SALA"));
        var aisle = librarian().post("/inventory/locations",
                Map.of("code", "P-1", "name", "Pasillo", "kind", "PASILLO",
                        "parentId", Long.valueOf(room.text("id"))));
        var shelf = librarian().post("/inventory/locations",
                Map.of("code", "E-1", "name", "Estante", "kind", "ESTANTE",
                        "parentId", Long.valueOf(aisle.text("id"))));
        librarian().post("/inventory/copies/bulk", Map.of(
                "bookId", bookId, "quantity", 2, "locationId", Long.valueOf(shelf.text("id"))));
    }

    private HttpTestClient librarian() {
        var client = new HttpTestClient(port);
        assertThat(client.post("/auth/login", Map.of(
                "email", DemoUsers.LIBRARIAN_EMAIL,
                "password", DemoUsers.LIBRARIAN_PASSWORD)).status()).isEqualTo(200);
        return client;
    }

    private long count(String table) {
        return jdbc.queryForObject("select count(*) from " + table, Long.class);
    }

    /** Wipes the library content but keeps the accounts so the client can sign in. */
    private void emptyTheLibrary() {
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
    }

    @Test
    void exportsTheWholeLibraryInOneVersionedDocument() {
        var exported = admin.get("/admin/export");

        assertThat(exported.status()).isEqualTo(200);
        assertThat(exported.json().path("schemaVersion").asInt()).isEqualTo(1);
        assertThat(exported.json().path("exportedAt").asText()).isNotEmpty();
        assertThat(exported.json().path("counts").path("books").asInt()).isEqualTo(1);
        assertThat(exported.json().path("counts").path("copies").asInt()).isEqualTo(2);
        assertThat(exported.json().path("books")).hasSize(1);
        assertThat(exported.json().path("books").get(0).path("isbn13").asText())
                .isEqualTo("9788491058106");
        assertThat(exported.json().path("locations")).hasSize(3);
        assertThat(exported.json().path("copies")).hasSize(2);
    }

    @Test
    void exportsTheAccountsSoTheReadersKeepThem() {
        var exported = admin.get("/admin/export");

        // Without the accounts, migrating means asking every reader to register again.
        assertThat(exported.json().path("users")).isNotEmpty();
        assertThat(exported.json().path("users").get(0).path("email").asText()).isNotEmpty();
        assertThat(exported.json().path("users").get(0).has("password_hash")).isTrue();
    }

    @Test
    void restoresAnEmptyedLibraryFromItsOwnExport() {
        var exported = admin.get("/admin/export");
        String document = exported.body();

        emptyTheLibrary();
        assertThat(count("books")).isZero();

        var imported = admin.postJson("/admin/import", document);

        assertThat(imported.status()).as("import: %s", imported.body()).isEqualTo(200);
        assertThat(count("books")).isEqualTo(1);
        assertThat(count("copies")).isEqualTo(2);
        assertThat(count("locations")).isEqualTo(3);
        assertThat(count("authors")).isEqualTo(1);
        assertThat(count("publishers")).isEqualTo(1);
        assertThat(count("categories")).isEqualTo(1);
        // The book must come back with its relationships intact, not as a bare row.
        assertThat(count("book_authors")).isEqualTo(1);
        assertThat(count("book_categories")).isEqualTo(1);
    }

    @Test
    void restoringTwiceDoesNotDuplicateAnything() {
        String document = admin.get("/admin/export").body();

        emptyTheLibrary();
        assertThat(admin.postJson("/admin/import", document).status()).isEqualTo(200);
        var again = admin.postJson("/admin/import", document);

        assertThat(again.status()).isEqualTo(200);
        assertThat(count("books")).isEqualTo(1);
        assertThat(count("copies")).isEqualTo(2);
        assertThat(count("locations")).isEqualTo(3);
        assertThat(again.json().path("created").path("books").asInt()).isZero();
        assertThat(again.json().path("updated").path("books").asInt()).isEqualTo(1);
    }

    @Test
    void keepsCopyCodesSoThePrintedLabelsStillMatch() {
        String document = admin.get("/admin/export").body();
        String code = admin.get("/admin/export").json().path("copies").get(0).path("code").asText();
        String barcode = admin.get("/admin/export").json().path("copies").get(0)
                .path("barcode").asText();

        emptyTheLibrary();
        admin.postJson("/admin/import", document);

        String restoredCode = jdbc.queryForObject(
                "select code from copies where barcode = ?", String.class, barcode);
        assertThat(restoredCode).isEqualTo(code);
    }

    @Test
    void refusesADocumentFromTheFuture() {
        var future = """
                {"schemaVersion": 99, "exportedAt": "2026-01-01T00:00:00Z",
                 "counts": {}, "publishers": [], "authors": [], "categories": [],
                 "books": [], "locations": [], "copies": [], "users": [],
                 "loans": [], "reservations": [], "appConfig": []}
                """;

        var refused = admin.postJson("/admin/import", future);

        assertThat(refused.status()).isEqualTo(400);
        assertThat(refused.text("code")).isEqualTo("unsupported_schema_version");
    }

    @Test
    void refusesSomethingThatIsNotAnExport() {
        var refused = admin.postJson("/admin/import", "{\"hola\":\"que tal\"}");

        assertThat(refused.status()).isEqualTo(400);
        assertThat(refused.text("code")).isEqualTo("not_an_export");
    }

    @Test
    void carriesLoansAndReservationsAcrossMachines() {
        // Found by the round 9 screenshots: importing a library that had any loan in
        // it used to fail with a 500, because the timestamps were bound without a SQL
        // type. No test had ever exported a document with loans in it.
        var room = librarian().post("/inventory/locations",
                Map.of("code", "SALA-9", "name", "Sala 9", "kind", "SALA"));
        var aisle = librarian().post("/inventory/locations",
                Map.of("code", "P-9", "name", "Pasillo 9", "kind", "PASILLO",
                        "parentId", Long.valueOf(room.text("id"))));
        var shelf = librarian().post("/inventory/locations",
                Map.of("code", "E-9", "name", "Estante 9", "kind", "ESTANTE",
                        "parentId", Long.valueOf(aisle.text("id"))));
        var copies = librarian().post("/inventory/copies/bulk",
                Map.of("bookId", bookId, "quantity", 2, "locationId", Long.valueOf(shelf.text("id"))));
        var copyId = copies.json().path("created").get(0).path("id").asLong();

        var readerId = Long.valueOf(
                signedIn(DemoUsers.READER_EMAIL, DemoUsers.READER_PASSWORD).get("/auth/me").text("id"));
        var loan = librarian().post("/loans", Map.of("copyId", copyId, "readerId", readerId));
        assertThat(loan.status()).as("borrow: %s", loan.body()).isEqualTo(201);

        // A different title for the reservation: the reader cannot queue for the one
        // they are holding, and cannot queue for one that is on the shelf.
        var clerk = signedIn(DemoUsers.CLERK_EMAIL, DemoUsers.CLERK_PASSWORD);
        var wanted = clerk.post("/catalog/books", Map.of(
                "title", "El nombre de la rosa",
                "isbn", "9788426403568",
                "authors", List.of(Map.of("name", "Umberto Eco", "role", "AUTOR"))));
        assertThat(wanted.status()).as("second book: %s", wanted.body()).isEqualTo(201);

        var reader = signedIn(DemoUsers.READER_EMAIL, DemoUsers.READER_PASSWORD);
        var reserved = reader.post("/loans/reservations",
                Map.of("bookId", Long.valueOf(wanted.text("id"))));
        assertThat(reserved.status()).as("reserve: %s", reserved.body()).isEqualTo(201);

        String document = admin.get("/admin/export").body();
        assertThat(document).contains("borrowed_at");

        emptyTheLibrary();

        var imported = admin.postJson("/admin/import", document);

        assertThat(imported.status()).as("import: %s", imported.body()).isEqualTo(200);
        assertThat(count("loans")).isEqualTo(1);
        assertThat(count("reservations")).isEqualTo(1);
        // The due date has to survive the trip, not come back as an epoch.
        String dueAt = jdbc.queryForObject("select due_at from loans limit 1", String.class);
        assertThat(dueAt).isNotNull();
        assertThat(dueAt).doesNotContain("1970");

        // Restoring the same document again must be a no-op: the copy is still out,
        // and loans_one_active_per_copy refuses a second open loan for it.
        var again = admin.postJson("/admin/import", document);

        assertThat(again.status()).as("second import: %s", again.body()).isEqualTo(200);
        assertThat(count("loans")).isEqualTo(1);
        assertThat(count("reservations")).isEqualTo(1);
        assertThat(again.json().path("skipped").path("loans").asInt()).isEqualTo(1);
    }

    private HttpTestClient signedIn(String email, String password) {
        var client = new HttpTestClient(port);
        assertThat(client.post("/auth/login", Map.of("email", email, "password", password)).status())
                .as("login %s", email).isEqualTo(200);
        return client;
    }

    @Test
    void bringsBackCopiesThatAreNotOnAShelfYet() {
        // Found by the round 9 report: 71 copies were being skipped on import because
        // a missing location was treated as an unrestorable row. A copy waiting to be
        // shelved is perfectly normal.
        var copies = librarian().post("/inventory/copies/bulk",
                Map.of("bookId", bookId, "quantity", 3));
        assertThat(copies.status()).as("stock: %s", copies.body()).isEqualTo(201);

        String document = admin.get("/admin/export").body();
        emptyTheLibrary();

        var imported = admin.postJson("/admin/import", document);

        assertThat(imported.status()).as("import: %s", imported.body()).isEqualTo(200);
        assertThat(imported.json().path("skipped").path("copies").asInt()).isZero();
        // Two copies were already shelved in setUp, three are waiting for a shelf.
        assertThat(count("copies")).isEqualTo(5);
        Integer withoutShelf = jdbc.queryForObject(
                "select count(*) from copies where location_id is null", Integer.class);
        assertThat(withoutShelf).isEqualTo(3);
        Integer shelved = jdbc.queryForObject(
                "select count(*) from copies where location_id is not null", Integer.class);
        assertThat(shelved).isEqualTo(2);
    }

    @Test
    void onlyAnAdministratorCanMoveTheWholeLibrary() {
        var librarian = librarian();
        var reader = new HttpTestClient(port);
        assertThat(reader.post("/auth/login", Map.of(
                "email", DemoUsers.READER_EMAIL,
                "password", DemoUsers.READER_PASSWORD)).status()).isEqualTo(200);

        assertThat(reader.get("/admin/export").status()).isEqualTo(403);
        assertThat(librarian.get("/admin/export").status()).isEqualTo(403);
        assertThat(librarian.postJson("/admin/import", "{}").status()).isEqualTo(403);
    }
}
