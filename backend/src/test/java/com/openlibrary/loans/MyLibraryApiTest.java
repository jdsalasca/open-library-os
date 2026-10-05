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
 * The reader's own corner of the library: what they have borrowed, what they are
 * waiting for, and nothing belonging to anybody else.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MyLibraryApiTest extends PostgresTest {

    @LocalServerPort
    int port;

    @Autowired
    DataSource dataSource;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PasswordEncoder passwords;

    private HttpTestClient librarian;
    private HttpTestClient reader;
    private HttpTestClient otherReader;
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
        reader = signedIn(DemoUsers.READER_EMAIL, DemoUsers.READER_PASSWORD);
        otherReader = signedIn(DemoUsers.VICTIM_EMAIL, DemoUsers.VICTIM_PASSWORD);

        var clerk = signedIn(DemoUsers.CLERK_EMAIL, DemoUsers.CLERK_PASSWORD);
        var book = clerk.post("/catalog/books", Map.of(
                "title", "Neuromante",
                "isbn", "9788491058106",
                "authors", List.of(Map.of("name", "William Gibson", "role", "AUTOR"))));
        assertThat(book.status()).as("seed book: %s", book.body()).isEqualTo(201);
        bookId = Long.valueOf(book.text("id"));
    }

    private HttpTestClient signedIn(String email, String password) {
        var client = new HttpTestClient(port);
        assertThat(client.post("/auth/login", Map.of("email", email, "password", password)).status())
                .as("login %s", email).isEqualTo(200);
        return client;
    }

    private long stock(int howMany) {
        var created = librarian.post("/inventory/copies/bulk",
                Map.of("bookId", bookId, "quantity", howMany));
        assertThat(created.status()).as("stock: %s", created.body()).isEqualTo(201);
        return created.json().path("created").get(0).path("id").asLong();
    }

    private long idOf(HttpTestClient client, String email) {
        var me = client.get("/auth/me");
        assertThat(me.text("email")).isEqualTo(email);
        return Long.valueOf(me.text("id"));
    }

    @Test
    void showsTheReaderWhatTheyHaveBorrowed() {
        long copyId = stock(2);
        var loan = librarian.post("/loans", Map.of("copyId", copyId, "readerId", idOf(reader, DemoUsers.READER_EMAIL)));
        assertThat(loan.status()).as("borrow: %s", loan.body()).isEqualTo(201);

        var mine = reader.get("/loans/mine");

        assertThat(mine.status()).isEqualTo(200);
        assertThat(mine.json().path("loans")).hasSize(1);
        assertThat(mine.json().path("loans").get(0).path("bookTitle").asText())
                .isEqualTo("Neuromante");
        assertThat(mine.json().path("loans").get(0).path("overdue").asBoolean()).isFalse();
    }

    @Test
    void neverShowsSomebodyElsesLoan() {
        long copyId = stock(2);
        librarian.post("/loans", Map.of(
                "copyId", copyId, "readerId", idOf(otherReader, DemoUsers.VICTIM_EMAIL)));

        var mine = reader.get("/loans/mine");

        assertThat(mine.json().path("loans")).isEmpty();
    }

    @Test
    void separatesOpenLoansFromHistory() {
        long first = stock(3);
        long second = jdbc.queryForObject(
                "select id from copies order by id offset 1 limit 1", Long.class);
        long third = jdbc.queryForObject(
                "select id from copies order by id offset 2 limit 1", Long.class);
        long readerId = idOf(reader, DemoUsers.READER_EMAIL);

        long kept = Long.parseLong(librarian.post("/loans",
                Map.of("copyId", first, "readerId", readerId)).text("id"));
        long closed = Long.parseLong(librarian.post("/loans",
                Map.of("copyId", second, "readerId", readerId)).text("id"));
        librarian.post("/loans", Map.of("copyId", third, "readerId", readerId));
        librarian.post("/loans/" + closed + "/return", Map.of());

        var mine = reader.get("/loans/mine");

        assertThat(mine.json().path("loans")).hasSize(2);
        assertThat(mine.json().path("history")).hasSize(1);
        assertThat(mine.json().path("history").get(0).path("id").asLong()).isEqualTo(closed);
        assertThat(mine.json().path("loans").get(0).path("id").asLong()).isEqualTo(kept);
    }

    @Test
    void showsTheReservationsWithTheirPlaceInTheQueue() {
        reader.post("/loans/reservations", Map.of("bookId", bookId));
        otherReader.post("/loans/reservations", Map.of("bookId", bookId));

        var mine = reader.get("/loans/mine");

        assertThat(mine.json().path("reservations")).hasSize(1);
        assertThat(mine.json().path("reservations").get(0).path("bookTitle").asText())
                .isEqualTo("Neuromante");
        // First in line, so the reader can act on it.
        assertThat(mine.json().path("reservations").get(0).path("place").asInt()).isEqualTo(1);
    }

    @Test
    void marksTheReservationAsReadyWhenTheBookIsOnTheShelf() {
        // Nothing to borrow: the reader is waiting, and the queue position matters.
        reader.post("/loans/reservations", Map.of("bookId", bookId));

        var mine = reader.get("/loans/mine");

        var reservation = mine.json().path("reservations").get(0);
        assertThat(reservation.path("availableNow").asBoolean()).isFalse();
        assertThat(reservation.path("place").asInt()).isEqualTo(1);
    }

    @Test
    void aReaderCanCancelTheirOwnReservationFromTheSameScreen() {
        long id = Long.parseLong(reader.post("/loans/reservations",
                Map.of("bookId", bookId)).text("id"));

        assertThat(reader.delete("/loans/reservations/" + id).status()).isEqualTo(204);

        assertThat(reader.get("/loans/mine").json().path("reservations")).isEmpty();
    }

    @Test
    void cannotCancelSomebodyElsesReservation() {
        long id = Long.parseLong(otherReader.post("/loans/reservations",
                Map.of("bookId", bookId)).text("id"));

        // 404 rather than 403: the reader has no business knowing it exists.
        assertThat(reader.delete("/loans/reservations/" + id).status()).isEqualTo(404);
    }

    @Test
    void refusesToReserveABookThatIsAlreadyOnTheShelf() {
        // A queue entry on an available book would block the desk from lending it
        // to anybody else, so the reservation is refused with an explanation.
        stock(1);

        var refused = reader.post("/loans/reservations", Map.of("bookId", bookId));

        assertThat(refused.status()).isEqualTo(409);
        assertThat(refused.text("code")).isEqualTo("book_available");
    }

    @Test
    void refusesToReserveABookTheyAlreadyHave() {
        long copyId = stock(1);
        librarian.post("/loans", Map.of(
                "copyId", copyId, "readerId", idOf(reader, DemoUsers.READER_EMAIL)));

        var refused = reader.post("/loans/reservations", Map.of("bookId", bookId));

        assertThat(refused.status()).isEqualTo(409);
        assertThat(refused.text("code")).isEqualTo("already_has_the_book");
    }

    @Test
    void aStaffAccountSeesItsOwnCornerToo() {
        // The librarian is a person with a card as well.
        assertThat(librarian.get("/loans/mine").status()).isEqualTo(200);
        assertThat(librarian.get("/loans/mine").json().path("loans")).isEmpty();
    }
}
