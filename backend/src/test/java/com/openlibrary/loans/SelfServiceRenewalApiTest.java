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
import tools.jackson.databind.JsonNode;

/**
 * Coming back to renew a book that is due tomorrow is one of the most common
 * reasons to walk into a library. The rules already exist in {@link LoanPolicy};
 * what is missing is letting the owner use them without standing at the desk.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SelfServiceRenewalApiTest extends PostgresTest {

    private static final String ANA_EMAIL = "ana.renueva@demo.test";
    private static final String BRUNO_EMAIL = "bruno.renueva@demo.test";

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

        librarian = signedIn(DemoUsers.LIBRARIAN_EMAIL, DemoUsers.LIBRARIAN_PASSWORD);
        var clerk = signedIn(DemoUsers.CLERK_EMAIL, DemoUsers.CLERK_PASSWORD);

        reader(ANA_EMAIL, "Ana Renovable");
        reader(BRUNO_EMAIL, "Bruno Renovable");

        var book = clerk.post("/catalog/books", Map.of(
                "title", "El nombre de la rosa",
                "isbn", "9780306406157",
                "authors", List.of(Map.of("name", "Umberto Eco", "role", "AUTOR"))));
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

    /** Lends one copy and returns its loan id. */
    private Long lendTo(String email) {
        var stock = librarian.post("/inventory/copies/bulk", Map.of("bookId", bookId, "quantity", 1));
        assertThat(stock.status()).as("stock: %s", stock.body()).isEqualTo(201);
        long copyId = stock.json().path("created").get(0).path("id").asLong();
        var userId = jdbc.queryForObject("select id from users where email = ?", Long.class, email);
        var lent = librarian.post("/loans", Map.of("copyId", copyId, "readerId", userId));
        assertThat(lent.status()).as("lend: %s", lent.body()).isEqualTo(201);
        return ((Number) lent.json().path("id").asLong()).longValue();
    }

    @Test
    void aReaderRenewsTheirOwnLoan() {
        long loanId = lendTo(ANA_EMAIL);
        var before = librarian.get("/loans?size=50").json();
        assertThat(readerDueDate(before, loanId)).isNotNull();

        var ana = signedIn(ANA_EMAIL, DemoUsers.READER_PASSWORD);
        var renewed = ana.post("/loans/" + loanId + "/renew", Map.of());

        assertThat(renewed.status()).as("renew: %s", renewed.body()).isEqualTo(200);
        assertThat(renewed.json().path("renewals").asInt()).isEqualTo(1);
        assertThat(renewed.json().path("dueAt").asText()).isNotBlank();
    }

    @Test
    void aReaderCannotRenewSomebodyElsesLoan() {
        long loanId = lendTo(BRUNO_EMAIL);

        var ana = signedIn(ANA_EMAIL, DemoUsers.READER_PASSWORD);
        var attempt = ana.post("/loans/" + loanId + "/renew", Map.of());

        assertThat(attempt.status()).isEqualTo(403);
    }

    @Test
    void aLoanThatDoesNotExistIsNotSomebodyElsesToGuessAt() {
        var ana = signedIn(ANA_EMAIL, DemoUsers.READER_PASSWORD);

        assertThat(ana.post("/loans/999999/renew", Map.of()).status()).isEqualTo(404);
    }

    @Test
    void theRulesStillApplyToSelfService() {
        long loanId = lendTo(ANA_EMAIL);
        jdbc.update("update loans set due_at = now() - interval '2 days' where id = ?", loanId);

        var ana = signedIn(ANA_EMAIL, DemoUsers.READER_PASSWORD);
        var overdue = ana.post("/loans/" + loanId + "/renew", Map.of());

        // Overdue is overdue whether you press the button at home or at the desk.
        assertThat(overdue.status()).isEqualTo(422);
        assertThat(overdue.body()).contains("vencido");
    }

    @Test
    void theRenewalCapIsTheSameForTheReader() {
        long loanId = lendTo(ANA_EMAIL);
        jdbc.update("update loans set renewals = 2 where id = ?", loanId);

        var ana = signedIn(ANA_EMAIL, DemoUsers.READER_PASSWORD);
        var capped = ana.post("/loans/" + loanId + "/renew", Map.of());

        assertThat(capped.status()).isEqualTo(422);
        assertThat(capped.body()).contains("renovo");
    }

    @Test
    void theDeskCanStillRenewAnybody() {
        long loanId = lendTo(BRUNO_EMAIL);

        assertThat(librarian.post("/loans/" + loanId + "/renew", Map.of()).status()).isEqualTo(200);
    }

    @Test
    void aReaderStillCannotBorrowOrReturnFromHome() {
        long loanId = lendTo(ANA_EMAIL);
        var ana = signedIn(ANA_EMAIL, DemoUsers.READER_PASSWORD);

        // Only renewal opens up. Borrowing and returning stay at the desk.
        assertThat(ana.post("/loans", Map.of("copyId", 1L, "readerId", 1L)).status()).isEqualTo(403);
        assertThat(ana.post("/loans/" + loanId + "/return", Map.of()).status()).isEqualTo(403);
    }

    private String readerDueDate(JsonNode page, long loanId) {
        for (var loan : page.path("content")) {
            if (loan.path("id").asLong() == loanId) {
                return loan.path("dueAt").asText();
            }
        }
        return null;
    }
}