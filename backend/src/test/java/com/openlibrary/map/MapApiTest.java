package com.openlibrary.map;

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

/** The map endpoint: one call has to carry everything a phone needs to draw it. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MapApiTest extends PostgresTest {

    @LocalServerPort
    int port;

    @Autowired
    DataSource dataSource;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PasswordEncoder passwords;

    private HttpTestClient librarian;

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

        librarian = new HttpTestClient(port);
        assertThat(librarian.post("/auth/login", Map.of(
                "email", DemoUsers.emailFor(Role.BIBLIOTECARIO),
                "password", DemoUsers.passwordFor(DemoUsers.emailFor(Role.BIBLIOTECARIO)))).status())
                .isEqualTo(200);

        var clerk = new HttpTestClient(port);
        assertThat(clerk.post("/auth/login", Map.of(
                "email", DemoUsers.emailFor(Role.ADMINISTRATIVO),
                "password", DemoUsers.passwordFor(DemoUsers.emailFor(Role.ADMINISTRATIVO)))).status())
                .isEqualTo(200);
        var book = clerk.post("/catalog/books", Map.of(
                "title", "Neuromante",
                "isbn", "9788491058106",
                "authors", List.of(Map.of("name", "William Gibson", "role", "AUTOR"))));
        assertThat(book.status()).as("seed book: %s", book.body()).isEqualTo(201);
        bookId = Long.valueOf(book.text("id"));
    }

    private Long bookId;

    private long location(String code, String kind, Long parent, String x, String z) {
        var body = new java.util.LinkedHashMap<String, Object>();
        body.put("code", code);
        body.put("name", code);
        body.put("kind", kind);
        if (parent != null) {
            body.put("parentId", parent);
        }
        if (x != null) {
            body.put("x", Double.parseDouble(x));
            body.put("z", Double.parseDouble(z));
            body.put("width", 4.0);
            body.put("depth", 2.0);
            body.put("height", 2.0);
        }
        var created = librarian.post("/inventory/locations", body);
        assertThat(created.status()).as("location %s: %s", code, created.body()).isEqualTo(201);
        return Long.valueOf(created.text("id"));
    }

    /** A shelf hangs from an aisle, never straight off a room. */
    private long aisleUnder(long room) {
        return location("P-1", "PASILLO", room, null, null);
    }

    @Test
    void returnsEveryNodeWithItsGeometryAndOccupancy() {
        long room = location("SALA-1", "SALA", null, "0", "0");
        long shelf = location("E-1", "ESTANTE", aisleUnder(room), "2", "3");
        var copies = librarian.post("/inventory/copies/bulk",
                Map.of("bookId", bookId, "quantity", 4, "locationId", shelf));
        assertThat(copies.status()).as("copies: %s", copies.body()).isEqualTo(201);

        var map = librarian.get("/map");

        assertThat(map.status()).isEqualTo(200);
        var nodes = map.json().path("nodes");
        // Room, aisle and shelf: the map is flat, the hierarchy lives in parentId.
        assertThat(nodes).hasSize(3);

        var byCode = new java.util.HashMap<String, tools.jackson.databind.JsonNode>();
        nodes.forEach(node -> byCode.put(node.path("code").asText(), node));

        assertThat(byCode.get("SALA-1").path("width").asDouble()).isEqualTo(4.0);
        assertThat(byCode.get("E-1").path("z").asDouble()).isEqualTo(3.0);
        assertThat(byCode.get("E-1").path("parentId").asLong())
                .as("el estante cuelga del pasillo").isEqualTo(
                        nodeByCode(map, "P-1").path("id").asLong());
    }

    @Test
    void countsOccupancyPerStatusOnEachShelf() {
        long room = location("SALA-1", "SALA", null, "0", "0");
        long shelf = location("E-1", "ESTANTE", aisleUnder(room), "1", "1");
        librarian.post("/inventory/copies/bulk",
                Map.of("bookId", bookId, "quantity", 3, "locationId", shelf));
        jdbc.update("update copies set status = 'PRESTADO' where id = (select min(id) from copies)");

        var map = librarian.get("/map");

        var shelf1 = nodeByCode(map, "E-1");
        assertThat(shelf1.path("copies").asInt()).isEqualTo(3);
        assertThat(shelf1.path("occupancy").path("DISPONIBLE").asInt()).isEqualTo(2);

        assertThat(shelf1.path("occupancy").path("PRESTADO").asInt()).isEqualTo(1);
    }

    @Test
    void listsWhatSitsOnAShelfSoTheMapCanDrillDown() {
        long room = location("SALA-1", "SALA", null, "0", "0");
        long shelf = location("E-1", "ESTANTE", aisleUnder(room), "1", "1");
        librarian.post("/inventory/copies/bulk",
                Map.of("bookId", bookId, "quantity", 2, "locationId", shelf));

        var items = nodeByCode(librarian.get("/map"), "E-1").path("items");

        assertThat(items).hasSize(2);
        assertThat(items.get(0).path("code").asText()).startsWith("OL-");
        assertThat(items.get(0).path("bookTitle").asText()).isEqualTo("Neuromante");
        assertThat(items.get(0).path("status").asText()).isEqualTo("DISPONIBLE");
    }

    @Test
    void aRoomThatHoldsNothingHasNoItems() {
        long room = location("SALA-1", "SALA", null, "0", "0");
        long shelf = location("E-1", "ESTANTE", aisleUnder(room), "1", "1");
        librarian.post("/inventory/copies/bulk", Map.of("bookId", bookId, "quantity", 1));

        assertThat(nodeByCode(librarian.get("/map"), "SALA-1").path("items")).isEmpty();
        assertThat(nodeByCode(librarian.get("/map"), "E-1").path("items")).isEmpty();
        assertThat(shelf).isPositive();
    }

    @Test
    void reportsBoundsSoTheViewCanFrameTheWholeLibrary() {
        location("SALA-1", "SALA", null, "0", "0");
        long aisle = location("P-1", "PASILLO", null, "10", "6");
        location("E-1", "ESTANTE", aisle, "12", "8");
        assertThat(aisle).isPositive();

        var bounds = librarian.get("/map").json().path("bounds");

        assertThat(bounds.path("minX").asDouble()).isLessThanOrEqualTo(0.0);
        assertThat(bounds.path("maxX").asDouble()).isGreaterThanOrEqualTo(12.0);
        assertThat(bounds.path("maxZ").asDouble()).isGreaterThanOrEqualTo(8.0);
        assertThat(bounds.path("width").asDouble()).isPositive();
    }

    @Test
    void laysOutUnmeasuredShelvesSoTheMapIsNeverEmpty() {
        // x/y/z are nullable on purpose: a library registers its shelves before
        // anyone walks the room with a measuring tape.
        long room = location("SALA-1", "SALA", null, null, null);
        long aisle = aisleUnder(room);
        location("E-1", "ESTANTE", aisle, null, null);
        location("E-2", "ESTANTE", aisle, null, null);

        var nodes = librarian.get("/map").json().path("nodes");

        // Room, aisle and two shelves.
        assertThat(nodes).hasSize(4);
        nodes.forEach(node -> {
            assertThat(node.path("x").isNumber()).as("%s x", node.path("code")).isTrue();
            assertThat(node.path("z").isNumber()).as("%s z", node.path("code")).isTrue();
        });
        // Two shelves at the same spot would be invisible; the fallback must spread them.
        double firstX = nodeByCode(librarian.get("/map"), "E-1").path("x").asDouble();
        double secondX = nodeByCode(librarian.get("/map"), "E-2").path("x").asDouble();
        assertThat(firstX).isNotEqualTo(secondX);
    }

    @Test
    void aReaderCanLookAtTheMapButNotStockShelves() {
        // The person holding the phone in a library is a reader looking for a
        // book, so the map is for everyone signed in. Stocking is not.
        var reader = new HttpTestClient(port);
        assertThat(reader.post("/auth/login", Map.of(
                "email", DemoUsers.READER_EMAIL,
                "password", DemoUsers.READER_PASSWORD)).status()).isEqualTo(200);

        assertThat(reader.get("/map").status()).isEqualTo(200);
        assertThat(reader.post("/inventory/locations", Map.of(
                "code", "E-9", "name", "E-9", "kind", "ESTANTE")).status()).isEqualTo(403);
    }

    private tools.jackson.databind.JsonNode nodeByCode(
            com.openlibrary.support.HttpTestClient.Result result, String code) {
        var nodes = result.json().path("nodes");
        for (var node : nodes) {
            if (node.path("code").asText().equals(code)) {
                return node;
            }
        }
        throw new AssertionError("no node " + code + " in " + result.body());
    }
}
