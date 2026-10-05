package com.openlibrary.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
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
import tools.jackson.databind.JsonNode;

import com.openlibrary.PostgresTest;
import com.openlibrary.support.DemoUsers;
import com.openlibrary.support.HttpTestClient;

/**
 * The 3D map is only as good as the geometry behind it, and until now that
 * geometry could be written but never read back: a fresh install had an empty
 * map and no way to place a shelf without calling the API by hand.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LocationGeometryApiTest extends PostgresTest {

    @LocalServerPort
    int port;

    @Autowired
    DataSource dataSource;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PasswordEncoder passwords;

    private HttpTestClient clerk;

    @BeforeEach
    void setUp() {
        DemoUsers.seed(dataSource, passwords);
        DemoUsers.resetPasswords(jdbc, passwords);
        jdbc.update("update users set must_change_password = false");
        jdbc.update("delete from copy_moves");
        jdbc.update("delete from copies");
        jdbc.update("delete from locations");

        clerk = new HttpTestClient(port);
        assertThat(clerk.post("/auth/login",
                Map.of("email", DemoUsers.CLERK_EMAIL, "password", DemoUsers.CLERK_PASSWORD)).status())
                .isEqualTo(200);
    }

    private Long createShelf(Map<String, Object> extra) {
        var body = new java.util.HashMap<String, Object>();
        body.put("code", "E-" + System.nanoTime());
        body.put("name", "Estanteria de prueba");
        body.put("kind", "ESTANTE");
        body.putAll(extra);
        var created = clerk.post("/inventory/locations", body);
        assertThat(created.status()).as("create: %s", created.body()).isEqualTo(201);
        return Long.valueOf(created.text("id"));
    }

    @Test
    void theGeometryComesBackSoTheEditorHasSomethingToShow() {
        long id = createShelf(Map.of(
                "x", new BigDecimal("2.50"),
                "y", new BigDecimal("0"),
                "z", new BigDecimal("-1.25"),
                "width", new BigDecimal("0.60"),
                "depth", new BigDecimal("0.35")));

        var node = find(clerk.get("/inventory/locations").json(), id);

        assertThat(node).isNotNull();
        assertThat(node.path("x").asDouble()).isEqualTo(2.5);
        assertThat(node.path("z").asDouble()).isEqualTo(-1.25);
        assertThat(node.path("width").asDouble()).isEqualTo(0.6);
        assertThat(node.path("depth").asDouble()).isEqualTo(0.35);
    }

    @Test
    void aShelfCanBeMovedAfterwards() {
        long id = createShelf(Map.of("x", new BigDecimal("0")));

        var moved = clerk.put("/inventory/locations/" + id, Map.of(
                "code", "E-1", "name", "Estanteria", "kind", "ESTANTE",
                "x", new BigDecimal("4.00"), "z", new BigDecimal("-3.00")));

        assertThat(moved.status()).as("move: %s", moved.body()).isEqualTo(200);
        var node = find(clerk.get("/inventory/locations").json(), id);
        assertThat(node.path("x").asDouble()).isEqualTo(4.0);
        assertThat(node.path("z").asDouble()).isEqualTo(-3.0);
    }

    @Test
    void coordinatesOutsideTheRoomAreRefused() {
        var far = clerk.post("/inventory/locations", Map.of(
                "code", "E-999", "name", "Lejos", "kind", "ESTANTE",
                "x", new BigDecimal("5000")));

        assertThat(far.status()).isEqualTo(400);
        assertThat(far.body()).contains("x");
    }

    @Test
    void aShelfWithoutGeometryIsStillAShelf() {
        long id = createShelf(Map.of());

        var node = find(clerk.get("/inventory/locations").json(), id);

// Absent means "not placed yet", not zero: the editor tells them apart.
        assertThat(node.has("x")).isFalse();
    }

    @Test
    void aReaderCannotMoveTheFurniture() {
        var reader = new HttpTestClient(port);
        assertThat(reader.post("/auth/login",
                Map.of("email", DemoUsers.READER_EMAIL, "password", DemoUsers.READER_PASSWORD)).status())
                .isEqualTo(200);

        var forbidden = reader.put("/inventory/locations/1", Map.of(
                "code", "E-1", "name", "Estanteria", "kind", "ESTANTE", "x", new BigDecimal("1")));

        assertThat(forbidden.status()).isEqualTo(403);
    }

private JsonNode find(JsonNode tree, long id) {
        for (var node : tree) {
            if (node.path("id").asLong() == id) {
                return node;
            }
            var child = find(node.path("children"), id);
            if (child != null && !child.isMissingNode()) {
                return child;
            }
        }
        return null;
    }
}