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
 * A library that lends three books for twenty days cannot keep lending four for
 * fourteen. Those two numbers live in `app_config`, and until now the only way
 * to change them was to open a psql shell — which is exactly the kind of thing
 * this project promises not to need.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LoanSettingsApiTest extends PostgresTest {

    @LocalServerPort
    int port;

    @Autowired
    DataSource dataSource;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PasswordEncoder passwords;

    private HttpTestClient admin;
    private HttpTestClient clerk;
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

        admin = signedIn(DemoUsers.ADMIN_EMAIL, DemoUsers.ADMIN_PASSWORD);
        clerk = signedIn(DemoUsers.CLERK_EMAIL, DemoUsers.CLERK_PASSWORD);
        librarian = signedIn(DemoUsers.LIBRARIAN_EMAIL, DemoUsers.LIBRARIAN_PASSWORD);
        resetSettings();
    }

    private void resetSettings() {
        jdbc.update("update app_config set value = '14' where key = 'loans.days_default'");
        jdbc.update("update app_config set value = '5' where key = 'loans.max_active_per_reader'");
        jdbc.update("update app_config set value = '2' where key = 'loans.max_renewals'");
    }

    private HttpTestClient signedIn(String email, String password) {
        var client = new HttpTestClient(port);
        assertThat(client.post("/auth/login", Map.of("email", email, "password", password)).status())
                .as("login %s", email).isEqualTo(200);
        return client;
    }

    @Test
    void readsThePolicyInUse() {
        var settings = admin.get("/loans/settings");

        assertThat(settings.status()).isEqualTo(200);
        assertThat(settings.json().path("loanDays").asInt()).isEqualTo(14);
        assertThat(settings.json().path("readerLimit").asInt()).isEqualTo(5);
        assertThat(settings.json().path("maxRenewals").asInt()).isEqualTo(2);
    }

    @Test
    void theDeskCanAlsoReadIt() {
        assertThat(librarian.get("/loans/settings").status()).isEqualTo(200);
    }

    @Test
    void anAdministratorChangesThePeriodAndItIsUsedAtOnce() {
        var updated = admin.put("/loans/settings", Map.of(
                "loanDays", 21, "readerLimit", 8, "maxRenewals", 1));

        assertThat(updated.status()).as("update: %s", updated.body()).isEqualTo(200);
        assertThat(admin.get("/loans/settings").json().path("loanDays").asInt()).isEqualTo(21);

        // Not just stored: the next loan obeys it.
        var book = book("ElNombreDeLaRosa");
        var copies = librarian.post("/inventory/copies/bulk", Map.of("bookId", book, "quantity", 1));
        long copyId = copies.json().path("created").get(0).path("id").asLong();
        var reader = reader();
        var lent = librarian.post("/loans", Map.of("copyId", copyId, "readerId", reader));
        assertThat(lent.status()).isEqualTo(201);

        var due = java.time.Instant.parse(lent.json().path("dueAt").asText());
        var days = java.time.Duration.between(java.time.Instant.now(), due).toDays();
        assertThat(days).isBetween(20L, 21L);
    }

    @Test
    void refusesNumbersThatWouldBreakTheShelves() {
        var zero = admin.put("/loans/settings", Map.of(
                "loanDays", 0, "readerLimit", 5, "maxRenewals", 2));
        assertThat(zero.status()).as("0 dias: %s", zero.body()).isEqualTo(400);
        assertThat(zero.body()).contains("loanDays");

        var silly = admin.put("/loans/settings", Map.of(
                "loanDays", 14, "readerLimit", 500, "maxRenewals", 2));
        assertThat(silly.status()).as("500 libros: %s", silly.body()).isEqualTo(400);
        assertThat(silly.body()).contains("readerLimit");
    }

    @Test
    void nothingIsChangedWhenPartOfTheUpdateIsNonsense() {
        admin.put("/loans/settings", Map.of("loanDays", 30, "readerLimit", 3, "maxRenewals", 1));

        var bad = admin.put("/loans/settings", Map.of("loanDays", 30, "readerLimit", 0, "maxRenewals", 1));

        assertThat(bad.status()).isEqualTo(400);
        // All or nothing: a rejected update must not half-apply.
        assertThat(admin.get("/loans/settings").json().path("loanDays").asInt()).isEqualTo(30);
        assertThat(admin.get("/loans/settings").json().path("readerLimit").asInt()).isEqualTo(3);
    }

    @Test
    void thePolicyIsNeverVisibleToAReader() {
        var reader = signedIn(DemoUsers.READER_EMAIL, DemoUsers.READER_PASSWORD);

        assertThat(reader.get("/loans/settings").status()).isEqualTo(403);
        assertThat(reader.put("/loans/settings",
                Map.of("loanDays", 99, "readerLimit", 99, "maxRenewals", 9)).status())
                .isEqualTo(403);
    }

    @Test
    void onlyAnAdministratorMayChangeThePolicy() {
        var payload = Map.of("loanDays", 99, "readerLimit", 99, "maxRenewals", 9);

        assertThat(clerk.put("/loans/settings", payload).status()).isEqualTo(403);
        assertThat(librarian.put("/loans/settings", payload).status()).isEqualTo(403);
    }

    private Long book(String title) {
        var response = clerk.post("/catalog/books", Map.of(
                "title", title,
                "authors", List.of(Map.of("name", "Umberto Eco", "role", "AUTOR"))));
        assertThat(response.status()).as("book: %s", response.body()).isEqualTo(201);
        return Long.valueOf(response.text("id"));
    }

    private long reader() {
        jdbc.update("insert into users (email, password_hash, full_name, role, must_change_password)"
                        + " values (?, ?, ?, ?, false)"
                        + " on conflict (lower(email)) do update set full_name = excluded.full_name",
                "ajustes.lector@demo.test", passwords.encode(DemoUsers.READER_PASSWORD),
                "Lector Ajustes", Role.LECTOR.name());
        return jdbc.queryForObject(
                "select id from users where email = 'ajustes.lector@demo.test'", Long.class);
    }
}