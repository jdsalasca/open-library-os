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
 * The first screen anybody sees has to answer "what needs doing today", not
 * "your database is up". These are the numbers a librarian acts on.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DashboardApiTest extends PostgresTest {

    private static final String ANA_EMAIL = "ana.dashboard@demo.test";
    private static final String BRUNO_EMAIL = "bruno.dashboard@demo.test";

    @LocalServerPort
    int port;

    @Autowired
    DataSource dataSource;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PasswordEncoder passwords;

    private HttpTestClient librarian;
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
        // Every test starts from the shipped defaults, not from the last one's edits.
        jdbc.update("update app_config set value = '5' where key = 'loans.max_active_per_reader'");

        librarian = signedIn(DemoUsers.LIBRARIAN_EMAIL, DemoUsers.LIBRARIAN_PASSWORD);
        var clerk = signedIn(DemoUsers.CLERK_EMAIL, DemoUsers.CLERK_PASSWORD);

        reader(ANA_EMAIL, "Anaeseed");
        reader(BRUNO_EMAIL, "Brunoeseed");

        var book = clerk.post("/catalog/books", Map.of(
                "title", "Cien anos de soledad",
                "isbn", "9780307474728",
                "authors", List.of(Map.of("name", "Gabriel Garcia Marquez", "role", "AUTOR"))));
        assertThat(book.status()).as("seed book: %s", book.body()).isEqualTo(201);
        bookId = Long.valueOf(book.text("id"));
    }

    private void reader(String email, String name) {
        jdbc.update("insert into users (email, password_hash, full_name, role, must_change_password)"
                        + " values (?, ?, ?, ?, false)"
                        + " on conflict (lower(email)) do update set full_name = excluded.full_name",
                email, passwords.encode(DemoUsers.READER_PASSWORD), name, Role.LECTOR.name());
    }

    private HttpTestClient signedIn(String email, String password) {
        var client = new HttpTestClient(port);
        assertThat(client.post("/auth/login", Map.of("email", email, "password", password)).status())
                .as("login %s", email).isEqualTo(200);
        return client;
    }

    private long idOf(String email) {
        return jdbc.queryForObject("select id from users where email = ?", Long.class, email);
    }

    /** Lends one copy and moves its due date by the given number of days. */
    private Long lendTo(String email, int dueInDays) {
        var stock = librarian.post("/inventory/copies/bulk", Map.of("bookId", bookId, "quantity", 1));
        assertThat(stock.status()).as("stock: %s", stock.body()).isEqualTo(201);
        long copyId = stock.json().path("created").get(0).path("id").asLong();
        assertThat(librarian.post("/loans", Map.of("copyId", copyId, "readerId", idOf(email))).status())
                .isEqualTo(201);
        jdbc.update("update loans set due_at = now() + (? || ' days')::interval"
                + " where copy_id = ? and returned_at is null", dueInDays, copyId);
        return copyId;
    }

    @Test
    void aQuietDayIsAllZeroes() {
        var dash = librarian.get("/loans/dashboard");

        assertThat(dash.status()).isEqualTo(200);
        assertThat(dash.json().path("out").asInt()).isZero();
        assertThat(dash.json().path("overdue").asInt()).isZero();
        assertThat(dash.json().path("dueToday").asInt()).isZero();
        assertThat(dash.json().path("available").asInt()).isZero();
        assertThat(dash.json().path("urgent").toString()).isEqualTo("[]");
    }

    @Test
    void countsWhatIsOutAndWhatCameBack() {
        lendTo(ANA_EMAIL, 7);
        var copy = lendTo(BRUNO_EMAIL, 7);
        assertThat(librarian.post("/loans/" + idOfLoan(copy) + "/return", Map.of()).status())
                .isEqualTo(200);

        var dash = librarian.get("/loans/dashboard");

        assertThat(dash.json().path("out").asInt()).as("out en %s", dash.body()).isEqualTo(1);
        // Two copies exist: one is still out, the other is back on the shelf.
        assertThat(dash.json().path("available").asInt()).isEqualTo(1);
    }

    @Test
    void countsOverdueAndDueTodaySeparately() {
        lendTo(ANA_EMAIL, -3);
        lendTo(BRUNO_EMAIL, 0);

        var dash = librarian.get("/loans/dashboard");

        assertThat(dash.json().path("overdue").asInt()).as("overdue en %s", dash.body()).isEqualTo(1);
        assertThat(dash.json().path("dueToday").asInt()).isEqualTo(1);
        assertThat(dash.json().path("out").asInt()).isEqualTo(2);
    }

    @Test
    void putsTheWorstDebtsFirstWithSomebodyToCall() {
        lendTo(ANA_EMAIL, -9);
        lendTo(BRUNO_EMAIL, -2);

        var urgent = librarian.get("/loans/dashboard").json().path("urgent");

        assertThat(urgent.isEmpty()).as("urgent vacio; respuesta %s", librarian.get("/loans/dashboard").body()).isFalse();
        assertThat(urgent.get(0).path("readerName").asText()).isEqualTo("Anaeseed");
        assertThat(urgent.get(0).path("bookTitle").asText()).isEqualTo("Cien anos de soledad");
        assertThat(urgent.get(0).path("readerEmail").asText()).isEqualTo(ANA_EMAIL);
        assertThat(urgent.get(0).path("daysLate").asInt()).isGreaterThanOrEqualTo(9);
    }

    @Test
    void theUrgentListIsShortEnoughToActOn() {
        // A busy day is not six loans: it is dozens. Lend normally first and
        // age the loans afterwards, because the policy (rightly) refuses to lend
        // to somebody who is already late. The per-reader limit is raised for
        // the same reason: this one reader is the cause of the whole pile.
        jdbc.update("update app_config set value = '50' where key = 'loans.max_active_per_reader'");
        for (int i = 0; i < 8; i++) {
            lendTo(ANA_EMAIL, 7);
        }
        jdbc.update("update loans set due_at = now() - interval '1 day'"
                + " where returned_at is null");

        var urgent = librarian.get("/loans/dashboard").json().path("urgent");

        assertThat(urgent.size()).isLessThanOrEqualTo(6);
        assertThat(librarian.get("/loans/dashboard").json().path("out").asInt()).isEqualTo(8);
    }

    @Test
    void doesNotCountReturnedCopiesAsOverdue() {
        var copy = lendTo(ANA_EMAIL, -5);
        librarian.post("/loans/" + idOfLoan(copy) + "/return", Map.of());

        assertThat(librarian.get("/loans/dashboard").json().path("overdue").asInt()).isZero();
    }

    @Test
    void aReaderCannotSeeTheLibrarianNumbers() {
        lendTo(ANA_EMAIL, -5);
        var reader = signedIn(ANA_EMAIL, DemoUsers.READER_PASSWORD);

        // Fail-closed: a card holder does not get to count other people's debts.
        assertThat(reader.get("/loans/dashboard").status()).isEqualTo(403);
    }

    private long idOfLoan(long copyId) {
        return jdbc.queryForObject("select id from loans where copy_id = ? order by id desc limit 1",
                Long.class, copyId);
    }
}
