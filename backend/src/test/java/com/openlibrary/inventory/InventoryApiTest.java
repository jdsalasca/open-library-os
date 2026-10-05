package com.openlibrary.inventory;

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
class InventoryApiTest extends PostgresTest {

    @LocalServerPort
    int port;

    @Autowired
    DataSource dataSource;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PasswordEncoder passwords;

    private HttpTestClient staff;
    private HttpTestClient reader;
    private Long bookId;

    @BeforeEach
    void setUp() {
        DemoUsers.seed(dataSource, passwords);
        DemoUsers.resetPasswords(jdbc, passwords);
        jdbc.update("update users set must_change_password = false");
        jdbc.update("delete from copy_moves");
        jdbc.update("delete from copies");
        jdbc.update("delete from locations");
        jdbc.update("delete from book_categories");
        jdbc.update("delete from book_authors");
        jdbc.update("delete from books");
        jdbc.update("delete from categories");
        jdbc.update("delete from authors");

        staff = signedIn(Role.BIBLIOTECARIO);
        reader = signedIn(Role.LECTOR);
        // Librarians stock shelves; the catalogue itself belongs to administrative staff.
        var clerk = signedIn(Role.ADMINISTRATIVO);

        var book = clerk.post("/catalog/books", Map.of(
                "title", "Neuromancer",
                "isbn", "9788491058106",
                "authors", List.of(Map.of("name", "William Gibson", "role", "AUTOR"))));
        assertThat(book.status()).as("seed book: %s", book.body()).isEqualTo(201);
        bookId = Long.valueOf(book.text("id"));
    }

    private HttpTestClient signedIn(Role role) {
        var client = new HttpTestClient(port);
        assertThat(client.post("/auth/login", Map.of(
                "email", DemoUsers.emailFor(role),
                "password", DemoUsers.passwordFor(DemoUsers.emailFor(role)))).status())
                .as("login as %s", role).isEqualTo(200);
        return client;
    }

    private long location(String code, String kind, Long parent) {
        var body = new java.util.LinkedHashMap<String, Object>();
        body.put("code", code);
        body.put("name", code);
        body.put("kind", kind);
        if (parent != null) {
            body.put("parentId", parent);
        }
        var created = staff.post("/inventory/locations", body);
        assertThat(created.status()).as("location %s: %s", code, created.body()).isEqualTo(201);
        return Long.valueOf(created.text("id"));
    }

    @Test
    void createsSeveralCopiesOfOneBookWithUniqueCodes() {
        var created = staff.post("/inventory/copies/bulk", Map.of(
                "bookId", bookId, "quantity", 3));

        assertThat(created.status()).isEqualTo(201);
        assertThat(created.json().path("created")).hasSize(3);

        Integer rows = jdbc.queryForObject("select count(*) from copies", Integer.class);
        Integer distinctBarcodes = jdbc.queryForObject(
                "select count(distinct barcode) from copies", Integer.class);
        assertThat(rows).isEqualTo(3);
        assertThat(distinctBarcodes).as("every copy scans differently").isEqualTo(3);
    }

    @Test
    void everyCopyCarriesTheBarcodeTheQrEncodes() {
        var created = staff.post("/inventory/copies/bulk", Map.of("bookId", bookId, "quantity", 1));
        var copy = created.json().path("created").get(0);

        assertThat(CopyCode.hasValidEan13Checksum(copy.path("barcode").asText())).isTrue();
        assertThat(copy.path("qr").asText()).isEqualTo(copy.path("code").asText());
    }

    @Test
    void rejectsABookThatDoesNotExist() {
        var created = staff.post("/inventory/copies/bulk",
                Map.of("bookId", 999_999, "quantity", 1));

        assertThat(created.status()).isEqualTo(404);
    }

    @Test
    void rejectsAnUnreasonableQuantity() {
        assertThat(staff.post("/inventory/copies/bulk", Map.of("bookId", bookId, "quantity", 0))
                .status()).isEqualTo(400);
        assertThat(staff.post("/inventory/copies/bulk", Map.of("bookId", bookId, "quantity", 500))
                .status()).isEqualTo(400);
    }

    @Test
    void assignsCopiesToAShelf() {
        long shelf = location("E-1", "ESTANTE", null);

        var created = staff.post("/inventory/copies/bulk", Map.of(
                "bookId", bookId, "quantity", 2, "locationId", shelf));

        var copyId = created.json().path("created").get(0).path("id").asLong();
        assertThat(staff.get("/inventory/copies/" + copyId).text("locationCode")).isEqualTo("E-1");
    }

    @Test
    void refusesToAssignACopyToACorridor() {
        long aisle = location("P-1", "PASILLO", null);

        var created = staff.post("/inventory/copies/bulk", Map.of(
                "bookId", bookId, "quantity", 1, "locationId", aisle));

        assertThat(created.status()).isEqualTo(400);
    }

    @Test
    void movesACopyAndKeepsTheHistory() {
        long from = location("E-1", "ESTANTE", null);
        long to = location("E-2", "ESTANTE", null);
        var copyId = staff.post("/inventory/copies/bulk",
                Map.of("bookId", bookId, "quantity", 1, "locationId", from))
                .json().path("created").get(0).path("id").asLong();

        var moved = staff.post("/inventory/copies/" + copyId + "/move",
                Map.of("toLocationId", to));

        assertThat(moved.status()).isEqualTo(200);
        assertThat(moved.text("locationCode")).isEqualTo("E-2");

        Integer history = jdbc.queryForObject(
                "select count(*) from copy_moves where copy_id = ?", Integer.class, copyId);
        assertThat(history).as("the initial placement is recorded too").isEqualTo(2);

        var moves = staff.get("/inventory/copies/" + copyId + "/moves");
        assertThat(moves.json().path(0).path("fromCode").asText()).isEqualTo("E-1");
        assertThat(moves.json().path(0).path("toCode").asText()).isEqualTo("E-2");
    }

    @Test
    void changingTheStatusIsRecorded() {
        var copyId = staff.post("/inventory/copies/bulk", Map.of("bookId", bookId, "quantity", 1))
                .json().path("created").get(0).path("id").asLong();

        assertThat(staff.patch("/inventory/copies/" + copyId + "/status",
                Map.of("status", "MANTENIMIENTO")).status()).isEqualTo(200);

        assertThat(staff.get("/inventory/copies/" + copyId).text("status"))
                .isEqualTo("MANTENIMIENTO");
    }

    @Test
    void filtersByStatusBookAndLocation() {
        long shelf = location("E-1", "ESTANTE", null);
        long other = location("E-2", "ESTANTE", null);
        var all = staff.post("/inventory/copies/bulk",
                Map.of("bookId", bookId, "quantity", 3, "locationId", shelf))
                .json().path("created");

        staff.patch("/inventory/copies/" + all.get(0).path("id").asLong() + "/status",
                Map.of("status", "MANTENIMIENTO"));
        staff.post("/inventory/copies/" + all.get(1).path("id").asLong() + "/move",
                Map.of("toLocationId", other));

        assertThat(staff.get("/inventory/copies").json().path("totalElements").asInt())
                .isEqualTo(3);
        assertThat(staff.get("/inventory/copies?status=MANTENIMIENTO").json()
                .path("totalElements").asInt()).isEqualTo(1);
        assertThat(staff.get("/inventory/copies?locationId=" + shelf).json()
                .path("totalElements").asInt()).isEqualTo(2);
        assertThat(staff.get("/inventory/copies?bookId=" + bookId).json()
                .path("totalElements").asInt()).isEqualTo(3);
        assertThat(staff.get("/inventory/copies?status=PRESTADO").json()
                .path("totalElements").asInt()).isZero();
    }

    @Test
    void searchesByCodeBarcodeOrBookTitle() {
        staff.post("/inventory/copies/bulk", Map.of("bookId", bookId, "quantity", 1));
        String humanCode = jdbc.queryForObject(
                "select code from copies order by id limit 1", String.class);
        String barcode = jdbc.queryForObject(
                "select barcode from copies order by id limit 1", String.class);

        assertThat(staff.get("/inventory/copies?q=" + humanCode).json()
                .path("totalElements").asInt()).isEqualTo(1);
        assertThat(staff.get("/inventory/copies?q=" + barcode).json()
                .path("totalElements").asInt()).isEqualTo(1);
        assertThat(staff.get("/inventory/copies?q=neuromancer").json()
                .path("totalElements").asInt()).isEqualTo(1);
    }

    @Test
    void listsTheLocationTree() {
        long room = location("SALA-1", "SALA", null);
        long aisle = location("P-1", "PASILLO", room);
        location("E-1", "ESTANTE", aisle);

        var tree = staff.get("/inventory/locations");

        assertThat(tree.json()).hasSize(1);
        assertThat(tree.json().get(0).path("code").asText()).isEqualTo("SALA-1");
        assertThat(tree.json().get(0).path("children")).hasSize(1);
        assertThat(tree.json().get(0).path("children").get(0).path("children")).hasSize(1);
    }

    @Test
    void rollsUpCopyCountsFromShelvesToTheirRoom() {
        long room = location("SALA-1", "SALA", null);
        long aisle = location("P-1", "PASILLO", room);
        long shelf = location("E-1", "ESTANTE", aisle);

        staff.post("/inventory/copies/bulk", Map.of(
                "bookId", bookId, "quantity", 3, "locationId", shelf));

        var root = staff.get("/inventory/locations").json().get(0);

        // The room and the aisle hold nothing themselves: the count must come from below.
        assertThat(root.path("copies").asInt()).isEqualTo(3);
        assertThat(root.path("children").get(0).path("copies").asInt()).isEqualTo(3);
        assertThat(root.path("children").get(0).path("children").get(0).path("copies").asInt())
                .isEqualTo(3);
    }

    @Test
    void refusesACycleInTheLocationTree() {
        long room = location("SALA-1", "SALA", null);

        assertThat(staff.put("/inventory/locations/" + room,
                Map.of("name", "SALA-1", "parentId", room)).status())
                .as("a room cannot be its own parent")
                .isEqualTo(400);
    }

    @Test
    void refusesToDeleteALocationThatStillHoldsCopies() {
        long shelf = location("E-1", "ESTANTE", null);
        staff.post("/inventory/copies/bulk",
                Map.of("bookId", bookId, "quantity", 1, "locationId", shelf));

        assertThat(staff.delete("/inventory/locations/" + shelf).status()).isEqualTo(409);
    }

    @Test
    void rendersAPrintableLabel() {
        var copyId = staff.post("/inventory/copies/bulk", Map.of("bookId", bookId, "quantity", 1))
                .json().path("created").get(0).path("id").asLong();

        var label = staff.getBytes("/inventory/copies/" + copyId + "/label.png");

        assertThat(label.status()).isEqualTo(200);
        assertThat(label.contentType()).contains("image/png");
        // PNG magic number, read as bytes because a text body would mangle them.
        assertThat(label.bytes()).startsWith(0x89, 'P', 'N', 'G');
        assertThat(label.bytes()).as("a blank label would be useless")
                .hasSizeGreaterThan(2_000);
    }

    @Test
    void readersCannotTouchTheInventory() {
        long shelf = location("E-1", "ESTANTE", null);

        assertThat(reader.post("/inventory/copies/bulk", Map.of("bookId", bookId, "quantity", 1))
                .status()).isEqualTo(403);
        assertThat(reader.post("/inventory/locations", Map.of(
                "code", "X", "name", "X", "kind", "ESTANTE")).status()).isEqualTo(403);
        assertThat(reader.get("/inventory/copies/" + shelf).status()).isEqualTo(403);
    }

    @Test
    void listsTheCatalogueWithCopyCounts() {
        staff.post("/inventory/copies/bulk", Map.of("bookId", bookId, "quantity", 2));

        var books = staff.get("/inventory/books");
        assertThat(books.status()).isEqualTo(200);
        assertThat(books.json().get(0).path("copies").asInt()).isEqualTo(2);
        assertThat(books.json().get(0).path("available").asInt()).isEqualTo(2);
    }
}