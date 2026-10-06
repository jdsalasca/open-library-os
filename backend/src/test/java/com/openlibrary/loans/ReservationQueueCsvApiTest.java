package com.openlibrary.loans;

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
 * When a book comes back, somebody is waiting and the desk has to call them. The
 * queue exists on screen as five indistinguishable rows of the same title, so the
 * call list has to come from somewhere.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ReservationQueueCsvApiTest extends PostgresTest {

    @LocalServerPort
    int port;

    @Autowired
    DataSource dataSource;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PasswordEncoder passwords;

    private HttpTestClient librarian;
    private HttpTestClient clerk;
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

        librarian = signedIn(DemoUsers.LIBRARIAN_EMAIL, DemoUsers.LIBRARIAN_PASSWORD);
        clerk = signedIn(DemoUsers.CLERK_EMAIL, DemoUsers.CLERK_PASSWORD);

        var book = clerk.post("/catalog/books", Map.of(
                "title", "Rayuela",
                "isbn", "9788437604572",
                "authors", List.of(Map.of("name", "Julio Cortázar", "role", "AUTOR"))));
        assertThat(book.status()).as("book: %s", book.body()).isEqualTo(201);
        bookId = Long.valueOf(book.text("id"));
    }

    private HttpTestClient signedIn(String email, String password) {
        var client = new HttpTestClient(port);
        assertThat(client.post("/auth/login", Map.of("email", email, "password", password)).status())
                .as("login %s", email).isEqualTo(200);
        return client;
    }

    private long reader(String email, String name) {
        jdbc.update("insert into users (email, password_hash, full_name, role, must_change_password)"
                        + " values (?, ?, ?, ?, false)"
                        + " on conflict (lower(email)) do update set full_name = excluded.full_name",
                email, passwords.encode(DemoUsers.READER_PASSWORD), name, Role.LECTOR.name());
        return jdbc.queryForObject("select id from users where email = ?", Long.class, email);
    }

    /** Lends the only copy away, so the book becomes reservable. */
    private void makeReservable() {
        var stock = librarian.post("/inventory/copies/bulk", Map.of("bookId", bookId, "quantity", 1));
        long copyId = stock.json().path("created").get(0).path("id").asLong();
        var owner = reader("dueno@demo.test", "Propietario");
        assertThat(librarian.post("/loans", Map.of("copyId", copyId, "readerId", owner)).status())
                .isEqualTo(201);
    }

    private void queue(String email, String name) {
        var id = reader(email, name);
        var reserved = signedIn(email, DemoUsers.READER_PASSWORD)
                .post("/loans/reservations", Map.of("bookId", bookId));
        assertThat(reserved.status()).as("reserve %s: %s", email, reserved.body()).isEqualTo(201);
        assertThat(reserved.text("userId")).isNotNull();
    }

    @Test
    void theQueueIsAWorkableList() {
        makeReservable();
        queue("uno@demo.test", "Ana Primera");
        queue("dos@demo.test", "Bruno Segundo");

        var body = librarian.get("/loans/reservations.csv").body();

        assertThat(body).startsWith("\uFEFF");
        assertThat(body).contains("libro,lector,correo,puesto,esperando_dias");
        assertThat(body).contains("Rayuela");
        assertThat(body).contains("Ana Primera");
        assertThat(body).contains("Bruno Segundo");
    }

    @Test
    void numbersTheQueueInTheOrderTheyArrived() {
        makeReservable();
        queue("uno@demo.test", "Ana Primera");
        queue("dos@demo.test", "Bruno Segundo");
        queue("tres@demo.test", "Carla Tercera");

        var body = librarian.get("/loans/reservations.csv").body();

        // The desk calls position 1 first: the order in the file is the work order.
        assertThat(body.indexOf("Ana Primera")).isLessThan(body.indexOf("Bruno Segundo"));
        assertThat(body.indexOf("Bruno Segundo")).isLessThan(body.indexOf("Carla Tercera"));
        assertThat(body).contains(",1,");
        assertThat(body).contains(",2,");
        assertThat(body).contains(",3,");
    }

    @Test
    void anEmptyQueueIsJustTheHeader() {
        var body = librarian.get("/loans/reservations.csv").body();

        assertThat(body).isEqualTo(
                "\uFEFFlibro,lector,correo,puesto,esperando_dias\r\n");
    }

    @Test
    void aReaderCannotDownloadSomebodyElsesQueue() {
        makeReservable();
        queue("uno@demo.test", "Ana Primera");

        var reader = signedIn(DemoUsers.READER_EMAIL, DemoUsers.READER_PASSWORD);

        assertThat(reader.get("/loans/reservations.csv").status()).isEqualTo(403);
    }
}